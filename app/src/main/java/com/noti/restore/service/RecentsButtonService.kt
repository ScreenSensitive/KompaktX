package com.noti.restore.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.media.AudioManager
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

        // Volume long press for media skip (only when media is playing)
        @Volatile var volumeLongPressMediaEnabled = false

        // Auto scroll mode (reserved for future use)
        @Volatile var scrollAutoModeEnabled = false

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

            // Assign each action to its trigger
            fun assign(trigger: Int, action: String) {
                when (trigger) {
                    ShortcutTrigger.LONG_PRESS_RECENTS -> longPressRecentsAction = action
                    ShortcutTrigger.DOUBLE_TAP_RECENTS -> doubleTapRecentsAction = action
                    ShortcutTrigger.LONG_PRESS_BACK -> longPressBackAction = action
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
                // Always use advanced handling when any customization is active — this
                // consumes ACTION_DOWN immediately so the system never fires its own recents
                if (OverlayPanelManager.triggerMode == TriggerMode.RECENTS_BUTTON ||
                    longPressRecentsAction.isNotEmpty() || doubleTapRecentsAction.isNotEmpty()) {
                    return handleRecentsAdvanced(event)
                }
            }

            KeyEvent.KEYCODE_BACK -> {
                // Long press back shortcut
                if (longPressBackAction.isNotEmpty()) {
                    return handleBackAdvanced(event)
                }
                // Default: close panel if open
                if (OverlayPanelManager.isPanelShowing) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        OverlayPanelManager.closePanelIfOpen()
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
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                recentsLongPressed = false
                recentsLongPressRunnable?.let { handler.removeCallbacks(it) }

                if (longPressRecentsAction.isNotEmpty()) {
                    recentsLongPressRunnable = Runnable {
                        recentsLongPressed = true
                        executeAction(longPressRecentsAction)
                    }
                    handler.postDelayed(recentsLongPressRunnable!!, LONG_PRESS_DELAY)
                }
                return true
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
                if (doubleTapRecentsAction.isNotEmpty() && (now - lastRecentsUpTime) < DOUBLE_TAP_TIMEOUT) {
                    recentsSingleTapRunnable?.let { handler.removeCallbacks(it) }
                    recentsSingleTapRunnable = null
                    lastRecentsUpTime = 0L
                    executeAction(doubleTapRecentsAction)
                    return true
                }

                lastRecentsUpTime = now

                if (doubleTapRecentsAction.isNotEmpty()) {
                    // Delay single tap to wait for possible second tap
                    recentsSingleTapRunnable?.let { handler.removeCallbacks(it) }
                    recentsSingleTapRunnable = Runnable {
                        performRecentsSingleTap()
                    }
                    handler.postDelayed(recentsSingleTapRunnable!!, DOUBLE_TAP_TIMEOUT)
                } else {
                    performRecentsSingleTap()
                }
                return true
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
                    executeAction(longPressBackAction)
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

                // Short press: close panel if open, otherwise perform system back
                if (OverlayPanelManager.isPanelShowing) {
                    OverlayPanelManager.closePanelIfOpen()
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

    private fun performRecentsSingleTap() {
        if (OverlayPanelManager.triggerMode == TriggerMode.RECENTS_BUTTON) {
            OverlayPanelManager.togglePanelFromService(this)
        } else {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
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
