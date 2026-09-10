package com.noti.restore.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.noti.restore.eink.MeinkController
import com.noti.restore.overlay.OverlayPanelManager
import com.noti.restore.ui.ShortcutTrigger
import com.noti.restore.ui.TriggerMode

/**
 * Accessibility service that intercepts hardware buttons.
 * Supports flexible shortcut assignments: any of 3 actions can be mapped
 * to Long Press Recents, Double Tap Recents, or Long Press Back.
 * Single tap Recents always toggles the panel (when trigger mode is RECENTS_BUTTON)
 * or opens system recents.
 */
class RecentsButtonService : AccessibilityService() {

    companion object {
        private const val TAG = "RecentsButtonService"
        private const val LONG_PRESS_DELAY = 400L
        private const val VOLUME_LONG_PRESS_DELAY = 60L
        private const val DOUBLE_TAP_TIMEOUT = 300L

        @Volatile var isRunning = false
            private set

        /**
         * Whether the user has enabled this service in system settings.
         *
         * Distinct from [isRunning], which is an in-process flag set when the system binds us. The
         * process dying resets that flag, and the system rebinds on its own schedule, so a
         * force-stop made a still-granted service look revoked. The setting is the actual grant
         * and outlives the process.
         */
        fun isAccessibilityGranted(context: Context): Boolean {
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val expected = ComponentName(context, RecentsButtonService::class.java)
                enabled.split(':').any { entry ->
                    val trimmed = entry.trim()
                    trimmed.equals(expected.flattenToString(), ignoreCase = true) ||
                        trimmed.equals(expected.flattenToShortString(), ignoreCase = true)
                }
            } catch (_: Exception) { false }
        }
        @Volatile private var instance: RecentsButtonService? = null
        @Volatile var currentForegroundPackage = ""

        /** Add a view as TYPE_ACCESSIBILITY_OVERLAY (draws above status bar). Returns false if service unavailable. */
        fun addHeadsUp(view: android.view.View, params: android.view.WindowManager.LayoutParams): Boolean {
            val svc = instance ?: return false
            return try {
                params.type = android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                (svc.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).addView(view, params)
                true
            } catch (_: Exception) { false }
        }
        fun updateHeadsUp(view: android.view.View, params: android.view.WindowManager.LayoutParams) {
            val svc = instance ?: return
            try { (svc.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).updateViewLayout(view, params) } catch (_: Exception) {}
        }
        fun removeHeadsUp(view: android.view.View) {
            val svc = instance ?: return
            try { (svc.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).removeView(view) } catch (_: Exception) {}
        }

        // Brightness overlay via TYPE_ACCESSIBILITY_OVERLAY — persists above lockscreen
        private var accessBrightnessView: android.view.View? = null
        private var accessBrightnessParams: android.view.WindowManager.LayoutParams? = null

        fun applyAccessibilityBrightness(screenBrightness: Float) {
            val svc = instance ?: return
            val wm = svc.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            val existing = accessBrightnessView
            val params = accessBrightnessParams
            if (existing != null && params != null) {
                params.screenBrightness = screenBrightness
                try { wm.updateViewLayout(existing, params) } catch (_: Exception) {}
            } else {
                val view = android.view.View(svc).apply { setBackgroundColor(0x00000000) }
                val p = android.view.WindowManager.LayoutParams(
                    1, 1,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply { this.screenBrightness = screenBrightness; gravity = android.view.Gravity.TOP or android.view.Gravity.START }
                try { wm.addView(view, p); accessBrightnessView = view; accessBrightnessParams = p } catch (_: Exception) {}
            }
        }

        fun clearAccessibilityBrightness() {
            val svc = instance ?: return
            val wm = svc.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            accessBrightnessView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            accessBrightnessView = null; accessBrightnessParams = null
        }


        // Shortcut trigger → action mapping (set from MainActivity)
        // Values are action strings: "front_light", "eink_refresh", "refresh_cycle", or "" for none
        @Volatile var longPressRecentsAction = ""
        @Volatile var doubleTapRecentsAction = ""
        @Volatile var longPressBackAction = ""
        @Volatile var doubleTapBackAction = ""

        // Volume long press for media skip (only when media is playing)
        @Volatile var volumeLongPressMediaEnabled = false

        /**
         * Gesture that opens a popup's reply box while a repliable popup is on screen.
         *
         * Takes precedence over whatever the same gesture normally does, but only for as long as
         * the popup is up — so it can share a gesture with a shortcut instead of consuming one.
         */
        @Volatile var popupReplyTrigger = ShortcutTrigger.NONE

        /** Whether [trigger] should open a reply right now rather than do its usual job. */
        fun popupReplyWantsTrigger(trigger: Int): Boolean =
            popupReplyTrigger != ShortcutTrigger.NONE &&
                popupReplyTrigger == trigger &&
                OverlayPanelManager.canReplyToHeadsUp

        /** Update all shortcut assignments at once from ShortcutTrigger values. */
        fun updateShortcuts(
            frontLightTrigger: Int,
            einkRefreshTrigger: Int,
            refreshCycleTrigger: Int
        ) {
            // Clear all
            longPressRecentsAction = ""
            doubleTapRecentsAction = ""
            longPressBackAction = ""
            doubleTapBackAction = ""

            // Assign each action to its trigger
            fun assign(trigger: Int, action: String) {
                when (trigger) {
                    ShortcutTrigger.LONG_PRESS_RECENTS -> longPressRecentsAction = action
                    ShortcutTrigger.DOUBLE_TAP_RECENTS -> doubleTapRecentsAction = action
                    ShortcutTrigger.LONG_PRESS_BACK -> longPressBackAction = action
                    ShortcutTrigger.DOUBLE_TAP_BACK -> doubleTapBackAction = action
                }
            }
            assign(frontLightTrigger, "front_light")
            assign(einkRefreshTrigger, "eink_refresh")
            assign(refreshCycleTrigger, "refresh_cycle")

            Log.d(TAG, "Shortcuts updated: LP_Recents=$longPressRecentsAction, DT_Recents=$doubleTapRecentsAction, LP_Back=$longPressBackAction")
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    // Recents: long press detection
    private var recentsLongPressRunnable: Runnable? = null
    private var recentsLongPressed = false

    // Recents: double tap detection
    private var recentsSingleTapRunnable: Runnable? = null
    private var lastRecentsUpTime = 0L

    // Back: long press detection
    private var backLongPressRunnable: Runnable? = null
    private var backLongPressed = false
    private var backSingleTapRunnable: Runnable? = null
    private var lastBackUpTime = 0L

    // Volume: long press detection for media skip
    private var volumeLongPressRunnable: Runnable? = null
    private var volumeLongPressed = false


    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this
        val info = AccessibilityServiceInfo().apply {
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        serviceInfo = info
        Log.d(TAG, "RecentsButtonService connected")
        OverlayPanelManager.reapplyBrightness()
    }

    override fun onInterrupt() { clearAccessibilityBrightness(); isRunning = false; instance = null }

    private var lastEinkPackage = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg.isBlank() || pkg == "android" || pkg == "com.android.systemui") return
        currentForegroundPackage = pkg
        if (!MeinkController.isAvailable || pkg == lastEinkPackage) return
        lastEinkPackage = pkg
        MeinkController.setMode(MeinkController.currentMode, pkg)
    }



    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        recentsLongPressRunnable?.let { handler.removeCallbacks(it) }
        recentsSingleTapRunnable?.let { handler.removeCallbacks(it) }
        backLongPressRunnable?.let { handler.removeCallbacks(it) }
        volumeLongPressRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_APP_SWITCH -> {
                // Only take the button when something is actually bound to it. A short press with
                // just a long-press shortcut assigned must still do whatever the button normally
                // does — see handleRecentsAdvanced, which passes those events straight through
                // rather than substituting an action the user never chose.
                if (OverlayPanelManager.triggerMode == TriggerMode.RECENTS_BUTTON ||
                    longPressRecentsAction.isNotEmpty() || doubleTapRecentsAction.isNotEmpty() ||
                    popupReplyWantsTrigger(ShortcutTrigger.LONG_PRESS_RECENTS) ||
                    popupReplyWantsTrigger(ShortcutTrigger.DOUBLE_TAP_RECENTS)) {
                    return handleRecentsAdvanced(event)
                }
            }

            KeyEvent.KEYCODE_BACK -> {
                // Long press back shortcut
                if (longPressBackAction.isNotEmpty() || doubleTapBackAction.isNotEmpty() ||
                    popupReplyWantsTrigger(ShortcutTrigger.LONG_PRESS_BACK) ||
                    popupReplyWantsTrigger(ShortcutTrigger.DOUBLE_TAP_BACK)) {
                    return handleBackAdvanced(event)
                }
                // Default: peel back one overlay layer — the long-press menu if it is up,
                // otherwise the panel itself.
                if (OverlayPanelManager.isPanelShowing) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        OverlayPanelManager.onBackPressed()
                    }
                    return true
                }
            }

            KeyEvent.KEYCODE_HOME -> {
                if (OverlayPanelManager.isPanelShowing) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        OverlayPanelManager.closePanelIfOpen()
                    }
                    return true
                }
            }

            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Claimed only for as long as a repliable popup is showing; volume control is
                // untouched at every other moment.
                val volTrigger = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                    ShortcutTrigger.VOLUME_UP else ShortcutTrigger.VOLUME_DOWN
                if (popupReplyWantsTrigger(volTrigger)) {
                    if (event.action == KeyEvent.ACTION_UP) OverlayPanelManager.openHeadsUpReply()
                    return true
                }
                if (volumeLongPressMediaEnabled && isMediaPlaying()) {
                    return handleVolumeAdvanced(event)
                }
            }
        }

        return false
    }

    /**
     * Advanced recents button handling:
     * - Long press → assigned action (if any)
     * - Double tap → assigned action (if any)
     * - Single tap → toggle panel / open recents
     */
    private fun handleRecentsAdvanced(event: KeyEvent): Boolean {
        // We only need to swallow the key when we might act on it ourselves. If the button is the
        // panel trigger, or a double tap is bound (which forces us to hold the first tap back to
        // see whether a second arrives), we own the event. Otherwise — the common case of a
        // long-press shortcut and nothing else — the press is passed through so a short press
        // still does whatever the button natively does.
        val ownsButton = OverlayPanelManager.triggerMode == TriggerMode.RECENTS_BUTTON ||
            doubleTapRecentsAction.isNotEmpty()

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                recentsLongPressed = false
                recentsLongPressRunnable?.let { handler.removeCallbacks(it) }

                if (longPressRecentsAction.isNotEmpty() ||
                    popupReplyWantsTrigger(ShortcutTrigger.LONG_PRESS_RECENTS)) {
                    recentsLongPressRunnable = Runnable {
                        recentsLongPressed = true
                        if (popupReplyWantsTrigger(ShortcutTrigger.LONG_PRESS_RECENTS)) {
                            OverlayPanelManager.openHeadsUpReply()
                        } else if (longPressRecentsAction.isNotEmpty()) {
                            executeAction(longPressRecentsAction)
                        }
                    }
                    handler.postDelayed(recentsLongPressRunnable!!, LONG_PRESS_DELAY)
                }
                return ownsButton
            }
            KeyEvent.ACTION_UP -> {
                recentsLongPressRunnable?.let { handler.removeCallbacks(it) }
                recentsLongPressRunnable = null

                if (recentsLongPressed) {
                    recentsLongPressed = false
                    return true
                }

                val now = System.currentTimeMillis()

                // Check for double tap
                val doubleTapWanted = doubleTapRecentsAction.isNotEmpty() ||
                    popupReplyWantsTrigger(ShortcutTrigger.DOUBLE_TAP_RECENTS)
                if (doubleTapWanted && (now - lastRecentsUpTime) < DOUBLE_TAP_TIMEOUT) {
                    recentsSingleTapRunnable?.let { handler.removeCallbacks(it) }
                    recentsSingleTapRunnable = null
                    lastRecentsUpTime = 0L
                    if (popupReplyWantsTrigger(ShortcutTrigger.DOUBLE_TAP_RECENTS)) {
                        OverlayPanelManager.openHeadsUpReply()
                    } else {
                        executeAction(doubleTapRecentsAction)
                    }
                    return true
                }

                lastRecentsUpTime = now

                if (doubleTapWanted) {
                    // Delay single tap to wait for possible second tap
                    recentsSingleTapRunnable?.let { handler.removeCallbacks(it) }
                    recentsSingleTapRunnable = Runnable {
                        performRecentsSingleTap()
                    }
                    handler.postDelayed(recentsSingleTapRunnable!!, DOUBLE_TAP_TIMEOUT)
                } else if (ownsButton) {
                    performRecentsSingleTap()
                }
                // When we don't own the button, report the press as unhandled so the system does
                // whatever it normally would with it.
                return ownsButton
            }
        }
        return true
    }

    /**
     * Advanced back button handling: long press → assigned action.
     * Short press → normal back (close panel if open, or pass through).
     */
    private fun handleBackAdvanced(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                backLongPressed = false
                backLongPressRunnable?.let { handler.removeCallbacks(it) }
                backLongPressRunnable = Runnable {
                    backLongPressed = true
                    if (popupReplyWantsTrigger(ShortcutTrigger.LONG_PRESS_BACK)) {
                        OverlayPanelManager.openHeadsUpReply()
                    } else if (longPressBackAction.isNotEmpty()) {
                        executeAction(longPressBackAction)
                    } else {
                        backLongPressed = false
                    }
                }
                handler.postDelayed(backLongPressRunnable!!, LONG_PRESS_DELAY)
                return true
            }
            KeyEvent.ACTION_UP -> {
                backLongPressRunnable?.let { handler.removeCallbacks(it) }
                backLongPressRunnable = null

                if (backLongPressed) {
                    backLongPressed = false
                    return true
                }

                val nowBack = System.currentTimeMillis()
                val backDoubleWanted = doubleTapBackAction.isNotEmpty() ||
                    popupReplyWantsTrigger(ShortcutTrigger.DOUBLE_TAP_BACK)
                if (backDoubleWanted && (nowBack - lastBackUpTime) < DOUBLE_TAP_TIMEOUT) {
                    backSingleTapRunnable?.let { handler.removeCallbacks(it) }
                    backSingleTapRunnable = null
                    lastBackUpTime = 0L
                    if (popupReplyWantsTrigger(ShortcutTrigger.DOUBLE_TAP_BACK)) {
                        OverlayPanelManager.openHeadsUpReply()
                    } else {
                        executeAction(doubleTapBackAction)
                    }
                    return true
                }
                lastBackUpTime = nowBack
                if (backDoubleWanted) {
                    // Hold the single press back long enough to see whether a second arrives.
                    backSingleTapRunnable?.let { handler.removeCallbacks(it) }
                    backSingleTapRunnable = Runnable { performBackSinglePress() }
                    handler.postDelayed(backSingleTapRunnable!!, DOUBLE_TAP_TIMEOUT)
                    return true
                }

                // Short press: peel back one overlay layer, otherwise perform system back
                if (OverlayPanelManager.isPanelShowing) {
                    OverlayPanelManager.onBackPressed()
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                return true
            }
        }
        return true
    }

    /**
     * Volume long press: skip next (vol up) or previous (vol down).
     * Vibrates on activation so user knows when to release.
     * Short press passes through for normal volume change.
     */
    private fun handleVolumeAdvanced(event: KeyEvent): Boolean {
        val isUp = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true // suppress auto-repeat
                volumeLongPressed = false
                volumeLongPressRunnable?.let { handler.removeCallbacks(it) }
                volumeLongPressRunnable = Runnable {
                    volumeLongPressed = true
                    vibrateShort()
                    if (isUp) {
                        KompaktXListener.nextExternalMedia()
                        Log.d(TAG, "Long press volume up → next track")
                    } else {
                        KompaktXListener.previousExternalMedia()
                        Log.d(TAG, "Long press volume down → previous track")
                    }
                }
                handler.postDelayed(volumeLongPressRunnable!!, VOLUME_LONG_PRESS_DELAY)
                return true
            }
            KeyEvent.ACTION_UP -> {
                volumeLongPressRunnable?.let { handler.removeCallbacks(it) }
                volumeLongPressRunnable = null

                if (volumeLongPressed) {
                    volumeLongPressed = false
                    return true
                }

                // Short press: do normal volume adjustment
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                val direction = if (isUp) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI
                )
                return true
            }
        }
        return true
    }

    private fun vibrateShort() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (!vibrator.hasVibrator()) {
                Log.w(TAG, "Device has no vibrator")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(150, 255))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(150)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate failed", e)
        }
    }

    private fun isMediaPlaying(): Boolean {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        return audioManager.isMusicActive
    }

    /** What a plain Back press does: peel one overlay layer, else hand Back to the system. */
    private fun performBackSinglePress() {
        if (OverlayPanelManager.isPanelShowing) {
            OverlayPanelManager.onBackPressed()
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun performRecentsSingleTap() {
        // The Recents button is only ours when it has been chosen as the panel trigger. In every
        // other configuration a plain press must do whatever the button natively does, so we do
        // nothing here rather than substituting an action the user never asked for.
        //
        // A double-tap shortcut is the one case that forces us to hold the first press back to
        // see whether a second follows; if it turns out to be a single tap there is nothing to
        // replay it with, so it is simply dropped. That is the cost of binding a double tap, and
        // it is preferable to the button doing something unexpected.
        if (OverlayPanelManager.triggerMode == TriggerMode.RECENTS_BUTTON) {
            OverlayPanelManager.togglePanelFromService(this)
        }
    }

    private fun executeAction(action: String) {
        Log.d(TAG, "Executing shortcut action: $action")
        when (action) {
            "front_light" -> OverlayPanelManager.toggleFrontLight(this)
            "eink_refresh" -> OverlayPanelManager.showEinkRefreshFlash(this)
            "refresh_cycle" -> OverlayPanelManager.cycleRefreshMode()
        }
    }
}
