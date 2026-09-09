package com.noti.restore.overlay

import android.annotation.SuppressLint
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager

import android.net.wifi.WifiManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.launch
import android.database.ContentObserver
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.noti.restore.eink.EinkMode
import com.noti.restore.eink.MeinkController
import com.noti.restore.service.KompaktXListener
import com.noti.restore.service.NotificationAction
import com.noti.restore.service.NotificationPopupData
import com.noti.restore.ui.RecentsLayoutMode
import com.noti.restore.ui.TriggerMode

private const val TAG = "OverlayPanelManager"

// Pure black/white literals — used by overlay theme switch.
private const val PURE_WHITE = 0xFFFFFFFF.toInt()
private const val PURE_BLACK = 0xFF000000.toInt()
private const val BORDER_WIDTH_DP = 2f

// Emoji regex for stripping from app labels
private val EMOJI_REGEX = Regex("[\\p{So}\\p{Sk}\\p{Cs}\\x{200D}\\x{FE0F}\\x{FE0E}\\x{20E3}\\x{E0020}-\\x{E007F}]+")

object OverlayPanelManager {

    private var windowManager: WindowManager? = null
    private var touchStripView: View? = null
    private var brightnessOverlay: View? = null  // Persistent full-screen overlay just for screenBrightness
    private var brightnessObserver: ContentObserver? = null  // Watches system brightness changes
    private var brightnessModeObserver: ContentObserver? = null  // Watches Adaptive Brightness on/off
    private var panelView: View? = null         // The root FrameLayout attached to WindowManager
    private var panelContentView: ViewGroup? = null  // The inner LinearLayout we swap content in
    /** The recents long-press menu while it is up, so BACK can dismiss it before the panel. */
    private var appActionsDialog: View? = null
    private var headsUpView: View? = null
    private var headsUpParams: WindowManager.LayoutParams? = null
    /** Holds a notification that arrived while a reply was being typed. */
    private val headsUpQueue = ArrayDeque<NotificationPopupData>()
    private var headsUpShowingKey: String? = null
    private val handler = Handler(Looper.getMainLooper())

    /** For preference writes made from the panel itself, e.g. the app long-press menu. */
    private val managerScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )
    private var dismissRunnable: Runnable? = null
    private var replyBoxOpen = false
    /** Send action for the popup currently on screen, shared by the button and the Enter key. */
    private var sendReplyNow: (() -> Unit)? = null

    // Swipe detection
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var isSwiping = false
    private const val SWIPE_THRESHOLD_DP = 50f

    // Heads-up swipe
    private var initialY = 0f
    private var initialTouchY = 0f

    // Settings
    var headsUpEnabled = true
    var recentAppsEnabled = true
    var triggerMode = TriggerMode.SWIPE_LEFT_EDGE
    var fontSizeOffset = 0
    var einkControlsEnabled = false
    var hapticsEnabled = true
    /** How long a heads-up popup stays on screen, in milliseconds. */
    var headsUpDurationMs = 5000L
    /** Send a popup reply on Enter, instead of only via the send button. */
    var replyOnEnter = false
    var showLocationToggle = false
    var hiddenNotiApps: Set<String> = emptySet()
    var hiddenRecentApps: Set<String> = emptySet()
    var recentsLayoutMode: Int = RecentsLayoutMode.INLINE
    var hideAppIcons: Boolean = false
    var tabPosition: Int = com.noti.restore.ui.TabPosition.TOP

    // Dark mode for the overlay UI — set from MainActivity based on theme pref + system uiMode.
    // Swaps every WHITE/BLACK reference within the overlay so the panel inverts cleanly.
    @Volatile var darkMode: Boolean = false

    // Semantic colors that follow [darkMode]. Defined as properties (not consts) so they
    // re-evaluate on every read and pick up theme changes without any per-site changes.
    private val WHITE: Int get() = if (darkMode) PURE_BLACK else PURE_WHITE
    private val BLACK: Int get() = if (darkMode) PURE_WHITE else PURE_BLACK

    // Track which WM was used for heads-up (accessibility overlay vs normal)
    private var headsUpUsingAccessibility = false

    // Fallback heights in dp (only used if measurement fails)
    private const val PAGE_NAV_HEIGHT_DP = 44f

    /**
     * How long after a wake / service reconnect a nonzero SCREEN_BRIGHTNESS is still attributed to
     * the OS restoring brightness itself rather than to the user changing it elsewhere. Generous
     * because the Kompakt's e-ink pipeline settles slowly after a wake.
     */
    private const val WAKE_RESTORE_GRACE_MS = 3000L

    /** How often the panel header's clock and battery re-read while the panel is open. */
    private const val CLOCK_REFRESH_MS = 60_000L

    /** Popups held back at once. Beyond this the oldest is dropped rather than shown very late. */
    private const val HEADS_UP_QUEUE_MAX = 5

    /**
     * How long to keep asserting "off" after an unexplained brightness rise before concluding a
     * person really did raise it elsewhere. Only needs to outlast the gap between the OS's
     * wake-time brightness write and the ACTION_SCREEN_ON broadcast that explains it.
     */
    private const val OFF_STATE_RELEASE_DELAY_MS = 2000L


    // Current context for recreating strip
    private var stripContext: Context? = null

    // Font sizes (base + offset)
    private val baseTitleSize get() = 14f + fontSizeOffset
    private val baseTextSize get() = 13f + fontSizeOffset
    private val baseSmallSize get() = 11f + fontSizeOffset
    private val baseTinySize get() = 10f + fontSizeOffset

    // ─── Touch Strip ────────────────────────────────────────────────

    fun createTouchStrip(context: Context) {
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.w(TAG, "No overlay permission")
            return
        }
        removeTouchStrip()
        stripContext = context
        loadSavedBrightness(context)

        // Mirror the system's brightness state onto our window overrides whenever anything —
        // the stock System UI slider, another app, or the OS's own adaptive curve — changes it.
        // This never writes back, so there is no feedback loop and no fight with the system:
        // whoever wrote the setting wins, and we simply make it reach the physical backlight.
        brightnessObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { syncBrightnessOverrides(context) }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false, brightnessObserver!!
        )

        // Adaptive Brightness being toggled (from anywhere) changes which authority owns the
        // backlight, so it needs the same mirror pass — see syncBrightnessOverrides().
        brightnessModeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { syncBrightnessOverrides(context) }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
            false, brightnessModeObserver!!
        )

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val dp = context.resources.displayMetrics.density

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        // Create persistent 1x1 overlay for screenBrightness (backup for when panel is open).
        // The ContentObserver handles lock screen brightness via Settings.System directly.
        if (brightnessOverlay == null) {
            brightnessOverlay = View(context).apply { setBackgroundColor(0x00000000) }
            val bParams = WindowManager.LayoutParams(
                1, 1, windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenBrightness = -1f
            }
            try { windowManager?.addView(brightnessOverlay, bParams) } catch (e: Exception) {
                Log.e(TAG, "Failed to add brightness overlay", e)
            }
        }

        if (triggerMode == TriggerMode.RECENTS_BUTTON) {
            Log.d(TAG, "Trigger mode: Recents button (no touch strip)")
            return
        }

        val swipeThresholdPx = SWIPE_THRESHOLD_DP * dp

        when (triggerMode) {
            TriggerMode.SWIPE_LEFT_EDGE -> {
                setupEdgeStrip(context, windowType, dp,
                    width = (16 * dp).toInt(), height = (80 * dp).toInt(),
                    gravityH = Gravity.START, gravityV = Gravity.TOP, yPos = 0
                ) { event ->
                    handleHorizontalSwipe(event, swipeThresholdPx, swipeRight = true) { togglePanel(context) }
                }
            }
            TriggerMode.SWIPE_RIGHT_EDGE -> {
                setupEdgeStrip(context, windowType, dp,
                    width = (16 * dp).toInt(), height = (80 * dp).toInt(),
                    gravityH = Gravity.END, gravityV = Gravity.TOP, yPos = 0
                ) { event ->
                    handleHorizontalSwipe(event, swipeThresholdPx, swipeRight = false) { togglePanel(context) }
                }
            }
            TriggerMode.PULL_UP_BOTTOM_RIGHT -> {
                setupEdgeStrip(context, windowType, dp,
                    width = (80 * dp).toInt(), height = (16 * dp).toInt(),
                    gravityH = Gravity.END, gravityV = Gravity.BOTTOM, yPos = 0
                ) { event ->
                    handleVerticalSwipe(event, swipeThresholdPx, swipeUp = true) { togglePanel(context) }
                }
            }
            TriggerMode.PULL_UP_BOTTOM_LEFT -> {
                setupEdgeStrip(context, windowType, dp,
                    width = (80 * dp).toInt(), height = (16 * dp).toInt(),
                    gravityH = Gravity.START, gravityV = Gravity.BOTTOM, yPos = 0
                ) { event ->
                    handleVerticalSwipe(event, swipeThresholdPx, swipeUp = true) { togglePanel(context) }
                }
            }
        }

        Log.d(TAG, "Touch strip created, triggerMode=$triggerMode")
    }

    fun removeTouchStrip() {
        touchStripView?.let { view ->
            try { windowManager?.removeView(view) } catch (_: Exception) {}
        }
        touchStripView = null
        brightnessOverlay?.let { view ->
            try { windowManager?.removeView(view) } catch (_: Exception) {}
        }
        brightnessOverlay = null
        com.noti.restore.service.RecentsButtonService.clearAccessibilityBrightness()
        brightnessObserver?.let { obs ->
            try { stripContext?.contentResolver?.unregisterContentObserver(obs) } catch (_: Exception) {}
        }
        brightnessObserver = null
        brightnessModeObserver?.let { obs ->
            try { stripContext?.contentResolver?.unregisterContentObserver(obs) } catch (_: Exception) {}
        }
        brightnessModeObserver = null
    }

    fun recreateTouchStrip() {
        stripContext?.let { createTouchStrip(it) }
    }

    val isPanelShowing: Boolean get() = panelView != null

    fun togglePanelFromService(context: Context) {
        if (windowManager == null) {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        stripContext = context
        handler.post {
            if (panelView != null) dismissPanel() else showPanel(context)
        }
    }

    /**
     * Handle a BACK press against the overlay, innermost layer first.
     *
     * Returns true when something was dismissed. The long-press menu sits inside the panel's own
     * window rather than in a window of its own, so nothing else would peel it off separately —
     * without this, one BACK took the menu and the panel together.
     */
    fun onBackPressed(): Boolean {
        appActionsDialog?.let { dialog ->
            handler.post {
                try { (panelView as? FrameLayout)?.removeView(dialog) } catch (_: Exception) {}
            }
            appActionsDialog = null
            return true
        }
        if (panelView != null) { closePanelIfOpen(); return true }
        return false
    }

    fun closePanelIfOpen() {
        handler.post { dismissPanel() }
    }

    /** Flash a full-screen black overlay to clear e-ink ghosting. */
    fun showEinkRefreshFlash(context: Context) {
        if (windowManager == null) {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        handler.post {
            try {
                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

                val flashView = View(context).apply { setBackgroundColor(PURE_BLACK) }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE
                )
                windowManager?.addView(flashView, params)

                // Hold black for 300ms, then flash white for 200ms, then remove
                handler.postDelayed({
                    try { flashView.setBackgroundColor(PURE_WHITE) } catch (_: Exception) {}
                    handler.postDelayed({
                        try { windowManager?.removeView(flashView) } catch (_: Exception) {}
                    }, 200)
                }, 300)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show e-ink refresh flash", e)
            }
        }
    }

    private var refreshRunnable: Runnable? = null

    /** Called from KompaktXListener when notifications change while panel is open.
     *  Debounced to avoid flashing on rapid changes (e.g. clearAll). */
    fun refreshPanelIfShowing() {
        val ctx = stripContext ?: return
        // Debounce: wait 300ms before rebuilding so multiple rapid changes batch together
        refreshRunnable?.let { handler.removeCallbacks(it) }
        refreshRunnable = Runnable {
            if (panelView != null) {
                allFlatNotifications = KompaktXListener.getNotifications()
                    .filter { !it.isOngoing && it.packageName !in hiddenNotiApps }
                    .sortedByDescending { it.postTime }
                buildAndShowPanel(ctx)
            }
        }
        handler.postDelayed(refreshRunnable!!, 300)
    }

    // ─── Touch Strip Helpers ────────────────────────────────────────

    private fun setupEdgeStrip(
        context: Context, windowType: Int, @Suppress("UNUSED_PARAMETER") dp: Float,
        width: Int, height: Int,
        gravityH: Int, gravityV: Int, yPos: Int,
        touchHandler: (MotionEvent) -> Boolean
    ) {
        touchStripView = View(context).apply { setBackgroundColor(0x00000000) }

        val params = WindowManager.LayoutParams(
            width, height, windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = gravityV or gravityH
            x = 0; y = yPos
        }

        touchStripView?.setOnTouchListener { _, event -> touchHandler(event) }

        try { windowManager?.addView(touchStripView, params) } catch (e: Exception) {
            Log.e(TAG, "Failed to add edge strip", e)
        }
    }

    private fun handleHorizontalSwipe(event: MotionEvent, threshold: Float, swipeRight: Boolean, onTrigger: () -> Unit): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.rawX; swipeStartY = event.rawY; isSwiping = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSwiping) {
                    val dx = if (swipeRight) event.rawX - swipeStartX else swipeStartX - event.rawX
                    val dy = Math.abs(event.rawY - swipeStartY)
                    if (dx > threshold && dx > dy * 1.5f) {
                        isSwiping = false; handler.post { onTrigger() }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isSwiping = false
        }
        return true
    }

    private fun handleVerticalSwipe(event: MotionEvent, threshold: Float, swipeUp: Boolean, onTrigger: () -> Unit): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.rawX; swipeStartY = event.rawY; isSwiping = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSwiping) {
                    val dy = if (swipeUp) swipeStartY - event.rawY else event.rawY - swipeStartY
                    val dx = Math.abs(event.rawX - swipeStartX)
                    if (dy > threshold && dy > dx * 1.5f) {
                        isSwiping = false; handler.post { onTrigger() }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isSwiping = false
        }
        return true
    }

    // ─── Notification Panel (full screen, no animation, no shadow, paginated) ────

    private var allFlatNotifications: List<StatusBarNotification> = emptyList()

    // Tab identifiers — values are stable across layout modes
    private val TAB_RECENTS = 0
    private val TAB_NOTIFICATIONS = 1
    private val TAB_SETTINGS = 2
    private var panelTab = TAB_NOTIFICATIONS

    /**
     * The trigger gesture is a toggle: the same swipe that opened the panel closes it again.
     *
     * For that to work while the panel is up, the edge strip has to sit above it — the panel
     * window is added later and would otherwise be on top and swallow the gesture. See
     * [bringTouchStripToFront].
     */
    private fun togglePanel(context: Context) {
        if (panelView != null) dismissPanel() else showPanel(context)
    }

    /**
     * Re-add the trigger strip so it sits above the panel window. Window z-order follows add
     * order, so removing and re-adding is the only way to raise it after the panel appears.
     */
    private fun bringTouchStripToFront() {
        val view = touchStripView ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        try {
            windowManager?.removeView(view)
            windowManager?.addView(view, lp)
        } catch (_: Exception) {}
    }

    private fun showPanel(context: Context) {
        if (panelView != null) return

        allFlatNotifications = KompaktXListener.getNotifications()
            .filter { !it.isOngoing && it.packageName !in hiddenNotiApps }
            .sortedByDescending { it.postTime }

        buildAndShowPanel(context)
    }


    private fun buildAndShowPanel(context: Context) {
        val dp = context.resources.displayMetrics.density
        val borderW = (BORDER_WIDTH_DP * dp).toInt().coerceAtLeast(2)
        val isNewWindow = panelView == null

        val rootFrame: FrameLayout
        val panelCard: LinearLayout

        if (isNewWindow) {
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            rootFrame = FrameLayout(context)

            // Backdrop behind the card. The card below is MATCH_PARENT in both axes, so there is
            // no visible "outside" for the user to tap — this exists only to paint the window
            // background. It must NOT be clickable: it used to carry a dismissPanel() listener,
            // which turned every tap that missed a clickable child into an accidental dismiss
            // (see panelCard.isClickable below).
            val scrim = View(context).apply { setBackgroundColor(WHITE) }
            rootFrame.addView(scrim, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            val cardBg = GradientDrawable().apply {
                setColor(WHITE)
                setStroke(borderW, BLACK)
            }

            panelCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg
                // Swallow taps that don't land on an interactive child. A non-clickable
                // ViewGroup returns false from onTouchEvent, so FrameLayout would hand the
                // event to the view *below* it — previously the dismiss-on-tap scrim. Because
                // this card covers the whole screen, that made every near-miss on a small
                // control (most visibly the tab pills, worst at tabPosition = BOTTOM where a
                // thumb lands low on the strip's dead padding) silently close the panel.
                isClickable = true
            }

            val panelLP = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            rootFrame.addView(panelCard, panelLP)

            panelView = rootFrame
            panelContentView = panelCard

            val wlp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                // NOT_FOCUSABLE matters as much as NOT_TOUCH_MODAL here. Without it this window
                // takes input focus the moment it is added, and the first tap after it appears
                // gets spent handing focus over rather than hitting what you aimed at — which is
                // why a tab press sometimes only registered on the second try. Nothing in the
                // panel takes text input (the reply box lives in the heads-up window), and BACK
                // is handled by the accessibility service, so focus buys us nothing.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP }

            try {
                windowManager?.addView(rootFrame, wlp)
                // Keep the trigger gesture reachable so the same swipe can close the panel.
                bringTouchStripToFront()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show panel", e)
                panelView = null
                panelContentView = null
                return
            }
        } else {
            rootFrame = panelView as FrameLayout
            panelCard = panelContentView as? LinearLayout ?: return
            // Clear existing content and rebuild in-place
            panelCard.removeAllViews()
        }

        // ── Top bar: date/time + close ──
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
        }
        val now = java.util.Calendar.getInstance()
        val dateStr = android.text.format.DateFormat.format("dd", now).toString()
        val monthYear = android.text.format.DateFormat.format("MMMM yyyy", now).toString()
        val dayOfWeek = android.text.format.DateFormat.format("EEEE", now).toString()
        // Follow the system's 12/24-hour setting rather than hardcoding 12-hour (issue #1).
        // getTimeFormat() resolves to the user's locale + "Use 24-hour format" preference, which
        // yields zero-padded 24-hour time (ISO-style) when that setting is on.
        val timeStr = android.text.format.DateFormat.getTimeFormat(context).format(now.time)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = batteryIntent?.let {
            val lvl = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (lvl >= 0 && scale > 0) lvl * 100 / scale else null
        }

        // Large date number
        topBar.addView(TextView(context).apply {
            text = dateStr; setTextColor(BLACK); textSize = baseTitleSize + 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, (8 * dp).toInt(), 0)
        })
        // Month/day/time column
        val dateCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        dateCol.addView(TextView(context).apply {
            text = monthYear; setTextColor(BLACK); textSize = baseSmallSize
            typeface = Typeface.DEFAULT_BOLD
        })
        // One status line: day, time and battery are all read-only facts, so they belong
        // together. Battery used to sit stacked above the close button, which put a readout and
        // a control in the same column and left the ✕ hanging low and off-centre.
        val clockLine = TextView(context).apply {
            text = buildString {
                append(dayOfWeek); append("  |  "); append(timeStr)
                if (batteryLevel != null) { append("  |  "); append("$batteryLevel%") }
            }
            setTextColor(BLACK); textSize = baseTinySize
        }
        dateCol.addView(clockLine)
        topBar.addView(dateCol)
        // The header is built once, so without this the clock freezes at whatever it read when the
        // panel opened. Re-read on the minute rather than every second: one partial refresh a
        // minute is unnoticeable on e-ink, sixty is not.
        startClockUpdates(context, clockLine)
        // Close, on its own and vertically centred against the date block.
        topBar.addView(TextView(context).apply {
            text = "\u2715"; setTextColor(BLACK); textSize = baseTitleSize + 2f
            gravity = android.view.Gravity.CENTER
            minWidth = (44 * dp).toInt()
            minHeight = (44 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL; marginStart = (8 * dp).toInt() }
            setOnClickListener { dismissPanel() }
        })
        panelCard.addView(topBar)

        // ── Tab switcher: small pill toggle (placed top or bottom per [tabPosition]) ──
        // In tabbed layout mode, Recents becomes its own tab to the LEFT of Notifications.
        val showRecentsTab = recentAppsEnabled && recentsLayoutMode == RecentsLayoutMode.TABBED
        val tabs: List<Pair<Int, String>> = buildList {
            if (showRecentsTab) add(TAB_RECENTS to "Recents")
            add(TAB_NOTIFICATIONS to "Notifications")
            add(TAB_SETTINGS to "Settings")
        }
        // Snap selection to a visible tab.
        if (tabs.none { it.first == panelTab }) panelTab = TAB_NOTIFICATIONS

        val tabBar = buildTabBar(context, dp, borderW, tabs)
        val tabContent = buildTabContent(context, dp, borderW)

        val placeTabsAtBottom = tabPosition == com.noti.restore.ui.TabPosition.BOTTOM
        if (!placeTabsAtBottom) {
            panelCard.addView(tabBar)
            panelCard.addView(makeThinDivider(context, dp))
        }
        panelCard.addView(tabContent)
        val showInlineRecents = recentAppsEnabled && recentsLayoutMode == RecentsLayoutMode.INLINE
        if (placeTabsAtBottom) {
            // The inline recents row below brings its own rule, so a second one directly above the
            // strip would put two hairlines within a few dp of each other.
            if (!showInlineRecents) panelCard.addView(makeThinDivider(context, dp))
            // A little more room beneath than above, so the pills don't read as falling off the
            // bottom edge of the panel.
            tabBar.setPadding(
                tabBar.paddingLeft, tabBar.paddingTop,
                tabBar.paddingRight, (8 * dp).toInt()
            )
            panelCard.addView(tabBar)
        }

        // ── Bottom bar: recents (inline layout only) ──
        if (showInlineRecents) {
            panelCard.addView(makeThinDivider(context, dp).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = (4 * dp).toInt()
            })
            val recentsSection = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            }
            recentsSection.addView(TextView(context).apply {
                text = "Recents"
                setTextColor(BLACK); textSize = baseTinySize; typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * dp).toInt() }
            })
            recentsSection.addView(buildRecentAppsRow(context, dp))
            panelCard.addView(recentsSection)
        }
    }

    private fun buildTabBar(
        context: Context, dp: Float, borderW: Int, tabs: List<Pair<Int, String>>
    ): View {
        val tabBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
        }
        val tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 100 * dp; setStroke(borderW, BLACK); setColor(WHITE)
            }
        }
        for ((tabId, label) in tabs) {
            val selected = tabId == panelTab
            val tabBg = GradientDrawable().apply {
                cornerRadius = 100 * dp
                if (selected) setColor(BLACK) else setColor(0x00000000)
            }
            tabContainer.addView(TextView(context).apply {
                text = label
                setTextColor(if (selected) WHITE else BLACK)
                textSize = baseSmallSize; typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER; background = tabBg
                // Same vertical padding as the WiFi/Bluetooth/Flashlight pills in the settings
                // grid, so the two sets of pill controls read as one family. A forced 44dp box
                // made these noticeably chunkier than those toggles.
                setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
                setOnClickListener {
                    if (panelTab != tabId) {
                        tapFeedback(context); panelTab = tabId; buildAndShowPanel(context)
                    }
                }
            })
        }
        tabBar.addView(tabContainer)
        return tabBar
    }

    private fun buildTabContent(context: Context, dp: Float, borderW: Int): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        when (panelTab) {
            TAB_RECENTS -> {
                val recentsScroll = ScrollView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    ); isVerticalScrollBarEnabled = false
                }
                recentsScroll.addView(buildRecentsListContent(context, dp))
                container.addView(recentsScroll)
            }
            TAB_NOTIFICATIONS -> {
                val mediaInfo = KompaktXListener.externalMediaInfo.value
                if (mediaInfo != null && mediaInfo.title != null) {
                    container.addView(buildMediaWidget(context, dp, mediaInfo))
                    container.addView(makeThinDivider(context, dp))
                }
                val notiScroll = ScrollView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    ); isVerticalScrollBarEnabled = false
                }
                val notiContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((14 * dp).toInt(), (4 * dp).toInt(), (14 * dp).toInt(), (4 * dp).toInt())
                }
                // Header row: title on the left, "Clear all" text-link on the right.
                // Same horizontal padding as the notification entries below for clean alignment.
                if (allFlatNotifications.isNotEmpty()) {
                    val header = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt())
                    }
                    header.addView(TextView(context).apply {
                        text = "${allFlatNotifications.size} notification" +
                            if (allFlatNotifications.size == 1) "" else "s"
                        setTextColor(BLACK); textSize = baseTinySize
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    header.addView(TextView(context).apply {
                        text = "Clear all"; setTextColor(BLACK); textSize = baseTinySize
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding((6 * dp).toInt(), (2 * dp).toInt(), 0, (2 * dp).toInt())
                        setOnClickListener {
                            refreshRunnable?.let { handler.removeCallbacks(it) }
                            KompaktXListener.clearAll(); dismissPanel()
                        }
                    })
                    notiContainer.addView(header)
                    notiContainer.addView(makeThinDivider(context, dp).apply {
                        (layoutParams as? LinearLayout.LayoutParams)?.apply {
                            marginStart = 0; marginEnd = 0
                            topMargin = 0; bottomMargin = (4 * dp).toInt()
                        }
                    })
                }
                if (allFlatNotifications.isEmpty()) {
                    notiContainer.addView(TextView(context).apply {
                        text = "No notifications"; setTextColor(BLACK); textSize = baseTitleSize
                        gravity = Gravity.CENTER
                        setPadding(0, (24 * dp).toInt(), 0, (24 * dp).toInt())
                    })
                } else {
                    allFlatNotifications.forEachIndexed { idx, sbn ->
                        notiContainer.addView(buildNotificationEntry(context, dp, sbn))
                        if (idx < allFlatNotifications.size - 1) notiContainer.addView(makeThinDivider(context, dp))
                    }
                }
                notiScroll.addView(notiContainer)
                container.addView(notiScroll)
            }
            TAB_SETTINGS -> {
                val settingsScroll = ScrollView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    ); isVerticalScrollBarEnabled = false
                }
                val settingsContent = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
                }
                settingsContent.addView(buildSettingsGrid(context, dp, borderW))
                settingsContent.addView(buildBrightnessSection(context, dp))
                settingsContent.addView(buildAdaptiveBrightnessToggle(context, dp, borderW))
                // Honour the user's toggle, not just hardware availability — einkControlsEnabled
                // was being set from settings and then never read, so switching it off left the
                // refresh controls on the panel.
                if (einkControlsEnabled && MeinkController.isAvailable) {
                    settingsContent.addView(buildRefreshModeCircles(context, dp, borderW))
                }
                settingsScroll.addView(settingsContent)
                container.addView(settingsScroll)
            }
        }
        return container
    }

    private fun buildAppHeader(context: Context, dp: Float, pkg: String, appName: String, count: Int): View {
        val appHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
        }
        if (!hideAppIcons) {
            val iconSize = (20 * dp).toInt()
            appHeader.addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = (6 * dp).toInt() }
                try { setImageDrawable(context.packageManager.getApplicationIcon(pkg)) } catch (_: Exception) {}
            })
        }
        appHeader.addView(TextView(context).apply {
            text = appName
            setTextColor(BLACK); textSize = baseSmallSize; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (count > 1) {
            appHeader.addView(TextView(context).apply {
                text = "$count"; setTextColor(BLACK); textSize = baseTinySize
            })
        }
        return appHeader
    }

    private fun makePageButton(context: Context, dp: Float, label: String, onClick: () -> Unit): View {
        val borderW = (BORDER_WIDTH_DP * dp).toInt().coerceAtLeast(1)
        val bg = GradientDrawable().apply {
            setColor(WHITE); setStroke(borderW, BLACK); cornerRadius = 8 * dp
        }
        return TextView(context).apply {
            text = label; setTextColor(BLACK); textSize = baseTitleSize; typeface = Typeface.DEFAULT_BOLD
            background = bg
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (4 * dp).toInt(); marginEnd = (4 * dp).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun dismissPanel() {
        appActionsDialog = null
        stopClockUpdates()
        panelView?.let { view ->
            try { windowManager?.removeView(view) } catch (_: Exception) {}
        }
        panelView = null
        panelContentView = null
        // Clear live brightness UI refs
        liveSunIcon = null
        liveSliderContainer = null
        liveSliderThumb = null
        liveSliderFill = null
        liveValueText = null
        liveAdaptivePill = null
        liveAdaptiveLabel = null
        liveAdaptiveBg = null
    }

    // ─── Notification Entry (no card outline, just content with dividers) ──

    private fun buildNotificationEntry(context: Context, dp: Float, sbn: StatusBarNotification): View {
        val extras = sbn.notification.extras
        val nTitle = extras.getString("android.title") ?: ""
        // Same newest-message extraction the heads-up pop-up uses, so a grouped SMS thread shows
        // the message that just arrived here in the tray too, not the oldest unread one.
        val nText = KompaktXListener.latestMessageText(sbn.notification, extras)
        val postTime = sbn.postTime
        val appName = try {
            val appInfo = context.packageManager.getApplicationInfo(sbn.packageName, 0)
            stripEmojis(context.packageManager.getApplicationLabel(appInfo).toString())
        } catch (_: Exception) { sbn.packageName.substringAfterLast(".") }

        val entry = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        // App icon on the left (skipped when hideAppIcons is on)
        if (!hideAppIcons) {
            val iconSize = (24 * dp).toInt()
            entry.addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = (8 * dp).toInt()
                    gravity = Gravity.CENTER_VERTICAL
                }
                try { setImageDrawable(context.packageManager.getApplicationIcon(sbn.packageName)) } catch (_: Exception) {}
            })
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Combined "AppName — Title" line, bold
        val headLine = if (nTitle.isNotEmpty()) "$appName — ${stripEmojis(nTitle)}" else appName
        textCol.addView(TextView(context).apply {
            text = headLine; setTextColor(BLACK); textSize = baseTextSize
            typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (nText.isNotEmpty()) {
            textCol.addView(TextView(context).apply {
                text = stripEmojis(nText); setTextColor(BLACK); textSize = baseTextSize
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
        }
        textCol.addView(TextView(context).apply {
            text = formatTime(postTime); setTextColor(BLACK); textSize = baseTinySize
        })
        entry.addView(textCol)

        entry.addView(TextView(context).apply {
            setText("\u2715"); setTextColor(BLACK); textSize = baseTitleSize
            // Pad to make the per-entry close target easier to hit too.
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
            minWidth = (40 * dp).toInt()
            setOnClickListener {
                KompaktXListener.dismiss(sbn.key)
                val parent = entry.parent as? ViewGroup ?: return@setOnClickListener
                val idx = parent.indexOfChild(entry)
                // Drop the divider that follows the entry (if any) so adjacent dividers
                // don't visually double up before the next refresh rebuilds the list.
                // For the last entry, drop the preceding divider instead.
                val sibling = if (idx + 1 < parent.childCount) parent.getChildAt(idx + 1)
                    else if (idx > 0) parent.getChildAt(idx - 1)
                    else null
                if (sibling != null && sibling.layoutParams.height <= (2 * dp).toInt() + 1) {
                    parent.removeView(sibling)
                }
                parent.removeView(entry)
            }
        })

        entry.setOnClickListener {
            KompaktXListener.openNotification(context, sbn.key, sbn.notification.contentIntent, sbn.packageName)
            dismissPanel()
        }

        return entry
    }

    // ─── Quick Toggles ────────────────────────────────────────────────

    private var flashlightOn = false
    private var lastManualBrightness = 128  // saved brightness for sun icon toggle
    private var lastAppliedOverride = Float.NaN  // last screenBrightness pushed to our windows
    /**
     * Whether the user has turned the front light fully off.
     *
     * Off has to be tracked as intent rather than read back from Settings.System, because the OS
     * does not preserve it: SCREEN_BRIGHTNESS accepts 0 while the screen is off, but the display
     * power controller rewrites a nonzero value of its own on the next wake (verified on the
     * Kompakt: write 0 -> sleep -> wake leaves the setting at 1). A pure mirror therefore reads
     * that value back on unlock and dutifully lights the panel again, which is exactly the
     * "off resets itself after unlocking" bug.
     *
     * What actually holds the panel dark is our sub-minimum window override, which the OS does not
     * touch. This flag is what tells the mirror pass to keep asserting it.
     */
    private var frontLightOff = false

    /**
     * True from the moment the screen goes off until shortly after it comes back on.
     *
     * This is how the OS's own wake-time brightness restore is told apart from a person changing
     * brightness in another app. The OS does not clamp our 0 to a floor — it discards it and
     * writes back the last nonzero brightness instead (verified on device: 100 -> 0 -> sleep ->
     * wake leaves the setting at 100). So there is no value we could recognise it by; only its
     * timing gives it away, and it lands before the SCREEN_ON broadcast arrives. Arming this while
     * the screen switches off is the only point that reliably precedes it.
     */
    private var pendingWakeRestore = false
    private var clearWakeRestoreRunnable: Runnable? = null
    private var offStateReleaseRunnable: Runnable? = null

    /** Whether the front light is currently off from the user's point of view. */
    private val isFrontLightOff: Boolean get() = frontLightOff

    /**
     * Brightness to restore when the front light is toggled back on. Ignores the current system
     * value while off, since that is the clamped floor rather than anything the user chose.
     */
    private fun frontLightRestoreValue(): Int = lastManualBrightness.coerceAtLeast(10)
    private var currentBrightnessTarget = -1  // tracks what brightness we last set (-1 = not managed)

    // Live references to brightness UI elements (set when panel is open, cleared on close)
    private var liveSunIcon: ImageView? = null
    private var liveSunSize = 0
    private var liveSliderContainer: FrameLayout? = null
    private var liveSliderThumb: View? = null
    private var liveSliderFill: View? = null
    private var liveSliderThumbSize = 0
    private var liveValueText: TextView? = null
    private var liveAdaptivePill: View? = null
    private var liveAdaptiveLabel: TextView? = null
    private var liveAdaptiveBg: ((Boolean) -> android.graphics.drawable.Drawable)? = null

    /** Load saved brightness from SharedPreferences */
    fun loadSavedBrightness(context: Context) {
        val sp = context.getSharedPreferences("kompaktx_brightness", Context.MODE_PRIVATE)
        lastManualBrightness = sp.getInt("last_manual_brightness", 128)
        currentBrightnessTarget = sp.getInt("current_brightness_target", -1)
        frontLightOff = sp.getBoolean("front_light_off", false)
        // Service (re)start is not a user gesture, so mirror the system's current state rather
        // than replaying a saved value — replaying it here is what used to silently flip Adaptive
        // Brightness back off every time the touch-strip service came up.
        syncBrightnessOverrides(context, force = true)
    }

    private fun saveBrightness(context: Context?) {
        context ?: return
        try {
            val sp = context.getSharedPreferences("kompaktx_brightness", Context.MODE_PRIVATE)
            sp.edit()
                .putInt("last_manual_brightness", lastManualBrightness)
                .putInt("current_brightness_target", currentBrightnessTarget)
                .putBoolean("front_light_off", frontLightOff)
                .apply()
        } catch (_: Exception) {}
    }
    private var activeSubPanel: String? = null  // "ink" or "brightness" or null
    private var subPanelHideRunnable: Runnable? = null
    private val SUB_PANEL_TIMEOUT = 4000L

    @SuppressLint("MissingPermission")
    private fun buildQuickToggles(context: Context, dp: Float): View {
        val borderW = (BORDER_WIDTH_DP * dp).toInt().coerceAtLeast(1)
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
        }

        data class Toggle(
            val id: String,
            val label: String,
            val isOn: () -> Boolean,
            val onTap: () -> Unit,
            val drawIcon: (Int, Boolean) -> Bitmap
        )

        val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val btAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val cameraMgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

        val toggles = mutableListOf<Toggle>()

        // WiFi — try direct toggle, fall back to settings panel
        if (wifiMgr != null) {
            toggles.add(Toggle("wifi", "WiFi",
                isOn = { wifiMgr.isWifiEnabled },
                onTap = {
                    try {
                        @Suppress("DEPRECATION")
                        val success = wifiMgr.setWifiEnabled(!wifiMgr.isWifiEnabled)
                        if (!success) {
                            context.startActivity(Intent(Settings.Panel.ACTION_WIFI)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    } catch (_: Exception) {
                        try {
                            context.startActivity(Intent(Settings.Panel.ACTION_WIFI)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {}
                    }
                },
                drawIcon = { size, on -> drawWifiIcon(size, on) }
            ))
        }

        // Bluetooth
        if (btAdapter != null) {
            toggles.add(Toggle("bt", "BT",
                isOn = { btAdapter.isEnabled },
                onTap = {
                    try {
                        if (btAdapter.isEnabled) btAdapter.disable() else btAdapter.enable()
                    } catch (_: Exception) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {}
                    }
                },
                drawIcon = { size, on -> drawBluetoothIcon(size, on) }
            ))
        }

        // Flashlight
        if (cameraMgr != null) {
            toggles.add(Toggle("flash", "Light",
                isOn = { flashlightOn },
                onTap = {
                    try {
                        val cameraId = cameraMgr.cameraIdList.firstOrNull() ?: return@Toggle
                        flashlightOn = !flashlightOn
                        cameraMgr.setTorchMode(cameraId, flashlightOn)
                    } catch (_: Exception) { flashlightOn = false }
                },
                drawIcon = { size, on -> drawFlashlightIcon(size, on) }
            ))
        }

        // Brightness — expandable sub-panel toggle
        toggles.add(Toggle("bright", "Bright",
            isOn = { activeSubPanel == "brightness" },
            onTap = {
                activeSubPanel = if (activeSubPanel == "brightness") null else "brightness"
            },
            drawIcon = { size, on -> drawBrightnessIcon(size, on) }
        ))

        for (toggle in toggles) {
            val on = toggle.isOn()
            val btnSize = (40 * dp).toInt()
            val iconInner = (22 * dp).toInt()
            val bg = GradientDrawable().apply {
                cornerRadius = 100 * dp
                if (on) setColor(BLACK) else { setColor(WHITE); setStroke(borderW, BLACK) }
            }
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            item.addView(ImageView(context).apply {
                setImageDrawable(BitmapDrawable(context.resources, toggle.drawIcon(iconInner, on)))
                scaleType = ImageView.ScaleType.CENTER
                background = bg
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                setOnClickListener {
                    toggle.onTap()
                    handler.postDelayed({ buildAndShowPanel(context) }, 150)
                }
            })
            item.addView(TextView(context).apply {
                text = toggle.label
                setTextColor(BLACK); textSize = baseTinySize
                gravity = Gravity.CENTER; maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (2 * dp).toInt(); gravity = Gravity.CENTER_HORIZONTAL }
            })
            row.addView(item)
        }
        wrapper.addView(row)

        // ── Sub-panel: Brightness slider ──
        if (activeSubPanel == "brightness") {
            wrapper.addView(buildBrightnessSubPanel(context, dp))
            scheduleSubPanelHide(context)
        }

        return wrapper
    }

    private fun scheduleSubPanelHide(context: Context) {
        subPanelHideRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (activeSubPanel != null) {
                activeSubPanel = null
                buildAndShowPanel(context)
            }
        }
        subPanelHideRunnable = r
        handler.postDelayed(r, SUB_PANEL_TIMEOUT)
    }

    private fun buildInkSubPanel(context: Context, dp: Float, borderW: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((14 * dp).toInt(), (2 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
        }
        for (mode in EinkMode.ALL) {
            val isSelected = MeinkController.currentMode == mode
            val bg = GradientDrawable().apply {
                cornerRadius = 100 * dp
                if (isSelected) setColor(BLACK) else { setColor(WHITE); setStroke(borderW, BLACK) }
            }
            row.addView(TextView(context).apply {
                text = EinkMode.label(mode)
                textSize = baseSmallSize; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSelected) WHITE else BLACK)
                gravity = Gravity.CENTER
                background = bg
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (3 * dp).toInt(); marginEnd = (3 * dp).toInt()
                }
                setOnClickListener {
                    MeinkController.setMode(mode)
                    // Reset hide timer
                    scheduleSubPanelHide(context)
                    handler.postDelayed({ buildAndShowPanel(context) }, 100)
                }
            })
        }
        return row
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBrightnessSubPanel(context: Context, dp: Float): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (2 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }

        val resolver = context.contentResolver
        // Report 0 while the front light is off: Settings.System holds the device's nonzero
        // floor in that state, which would otherwise render as a few percent instead of "Off".
        val currentBrightness = if (isFrontLightOff) 0 else try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { 128 }

        // Deliberately does NOT force manual mode here. Building a view is not a user gesture —
        // stock Android only leaves Adaptive Brightness when you actually drag the slider, and so
        // do we (see the ACTION_DOWN/ACTION_MOVE branch below).

        val trackH = (6 * dp).toInt()
        val thumbSize = (24 * dp).toInt()

        // Value label
        val valueText = TextView(context).apply {
            text = "${(currentBrightness * 100 / 255)}%"
            setTextColor(BLACK); textSize = baseSmallSize; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * dp).toInt() }
        }

        // Custom slider using a FrameLayout
        val sliderContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, (32 * dp).toInt(), 1f)
        }

        val track = View(context).apply {
            val trackBg = GradientDrawable().apply {
                setColor(WHITE); setStroke((2 * dp).toInt().coerceAtLeast(1), BLACK)
                cornerRadius = trackH / 2f
            }
            background = trackBg
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, trackH
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        }
        sliderContainer.addView(track)

        // Fill bar
        val fill = View(context).apply {
            background = GradientDrawable().apply {
                setColor(BLACK); cornerRadius = trackH / 2f
            }
        }
        sliderContainer.addView(fill, FrameLayout.LayoutParams(0, trackH).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        })

        // Thumb
        val thumb = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(BLACK)
            }
            layoutParams = FrameLayout.LayoutParams(thumbSize, thumbSize).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        sliderContainer.addView(thumb)

        // Position thumb/fill after layout
        fun updateSliderVisual(brightness: Int, width: Int) {
            val usable = width - thumbSize
            val frac = brightness / 255f
            val thumbX = (frac * usable).toInt()
            (thumb.layoutParams as FrameLayout.LayoutParams).leftMargin = thumbX
            thumb.requestLayout()
            (fill.layoutParams as FrameLayout.LayoutParams).width = thumbX + thumbSize / 2
            fill.requestLayout()
            valueText.text = "${(brightness * 100 / 255)}%"
        }

        sliderContainer.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            var done = false
            override fun onGlobalLayout() {
                if (done) return; done = true
                sliderContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                updateSliderVisual(currentBrightness, sliderContainer.width)
            }
        })

        sliderContainer.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val frac = ((event.x - thumbSize / 2) / (v.width - thumbSize)).coerceIn(0f, 1f)
                    val brightness = (frac * 255).toInt().coerceIn(1, 255)
                    // A real drag: take manual ownership of the backlight, exactly as stock
                    // Android's slider does. Previously this wrote SCREEN_BRIGHTNESS directly and
                    // relied on the mode being forced when the sub-panel was built.
                    setManualBrightness(resolver, brightness)
                    updateSliderVisual(brightness, v.width)
                    // Reset hide timer on interaction
                    scheduleSubPanelHide(context)
                    true
                }
                else -> true
            }
        }

        row.addView(sliderContainer)
        row.addView(valueText)
        return row
    }

    // ─── Quick Toggle Icons (pure B/W Canvas) ──────────────────────

    /**
     * Turn location on or off, returning false if the system refused and the caller should fall
     * back to opening the location settings screen.
     *
     * Both paths need WRITE_SECURE_SETTINGS, which cannot be requested at runtime but can be
     * granted over adb:
     *     adb shell pm grant com.kompaktx.app android.permission.WRITE_SECURE_SETTINGS
     *
     * LocationManager.setLocationEnabledForUser() is the supported route since Android P — it is
     * hidden @SystemApi, hence the reflection. Settings.Secure.LOCATION_MODE was deprecated in the
     * same release and writing it no longer reliably drives the providers from an app, even though
     * the identical write still works from an adb shell; it is kept only as a fallback for older
     * or non-standard builds.
     */
    private fun setLocationEnabled(
        context: Context,
        manager: android.location.LocationManager,
        enable: Boolean
    ): Boolean {
        try {
            val method = android.location.LocationManager::class.java.getMethod(
                "setLocationEnabledForUser", Boolean::class.javaPrimitiveType, android.os.UserHandle::class.java
            )
            method.invoke(manager, enable, android.os.Process.myUserHandle())
            if (manager.isLocationEnabled == enable) return true
        } catch (_: Throwable) {}
        return try {
            Settings.Secure.putInt(
                context.contentResolver, Settings.Secure.LOCATION_MODE,
                if (enable) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                else Settings.Secure.LOCATION_MODE_OFF
            )
            manager.isLocationEnabled == enable
        } catch (_: Exception) { false }
    }

    /** Map-pin glyph; struck through when location is off, matching the other toggle icons. */
    private fun drawLocationIcon(size: Int, on: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (on) WHITE else BLACK
            style = Paint.Style.STROKE
            strokeWidth = size * 0.1f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val cx = size / 2f
        val headR = size * 0.22f
        val headCy = size * 0.38f
        canvas.drawCircle(cx, headCy, headR, paint)
        // Tapered tail down to the point.
        canvas.drawLine(cx - headR * 0.72f, headCy + headR * 0.72f, cx, size * 0.86f, paint)
        canvas.drawLine(cx + headR * 0.72f, headCy + headR * 0.72f, cx, size * 0.86f, paint)
        if (!on) canvas.drawLine(size * 0.16f, size * 0.16f, size * 0.84f, size * 0.84f, paint)
        return bmp
    }

    private fun drawWifiIcon(size: Int, on: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val color = if (on) WHITE else BLACK
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE
            strokeWidth = size * 0.105f; strokeCap = Paint.Cap.ROUND
        }
        // Every arc and the dot share one origin so the fan converges on a single point. The
        // previous bounds put each arc's centre at bottom - r, which moved with the radius, so
        // the three arcs were struck about different centres and never lined up.
        val cx = size / 2f
        val originY = size * 0.80f
        for (i in 1..3) {
            val r = size * 0.19f * i
            c.drawArc(cx - r, originY - r, cx + r, originY + r, -132f, 84f, false, p)
        }
        p.style = Paint.Style.FILL
        c.drawCircle(cx, originY, size * 0.075f, p)
        return bmp
    }

    private fun drawBluetoothIcon(size: Int, on: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (on) WHITE else BLACK
            style = Paint.Style.STROKE
            strokeWidth = size * 0.1f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val top = size * 0.1f; val bot = size * 0.9f
        val cx = size / 2f; val right = size * 0.7f; val left = size * 0.3f
        val path = Path().apply {
            moveTo(left, top + (bot - top) * 0.25f)
            lineTo(right, top + (bot - top) * 0.75f)
            lineTo(cx, bot); lineTo(cx, top)
            lineTo(right, top + (bot - top) * 0.25f)
            lineTo(left, top + (bot - top) * 0.75f)
        }
        c.drawPath(path, p)
        return bmp
    }

    private fun drawFlashlightIcon(size: Int, on: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (on) WHITE else BLACK
            style = Paint.Style.STROKE
            strokeWidth = size * 0.1f; strokeCap = Paint.Cap.ROUND
        }
        val bodyL = size * 0.3f; val bodyR = size * 0.7f
        val top = size * 0.1f; val taper = size * 0.35f; val bot = size * 0.9f
        c.drawLine(bodyL, top, size * 0.25f, taper, p)
        c.drawLine(bodyR, top, size * 0.75f, taper, p)
        c.drawLine(bodyL, top, bodyR, top, p)
        c.drawLine(size * 0.25f, taper, size * 0.75f, taper, p)
        c.drawLine(size * 0.35f, taper, size * 0.35f, bot, p)
        c.drawLine(size * 0.65f, taper, size * 0.65f, bot, p)
        c.drawLine(size * 0.35f, bot, size * 0.65f, bot, p)
        if (on) {
            p.strokeWidth = size * 0.06f
            val cx = size / 2f
            c.drawLine(cx, size * 0.02f, cx, size * 0.08f, p)
            c.drawLine(size * 0.15f, size * 0.12f, size * 0.22f, size * 0.18f, p)
            c.drawLine(size * 0.85f, size * 0.12f, size * 0.78f, size * 0.18f, p)
        }
        return bmp
    }

    private fun drawSoundIcon(size: Int, on: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (on) WHITE else BLACK
            strokeWidth = size * 0.1f; strokeCap = Paint.Cap.ROUND
        }
        val spL = size * 0.15f; val spR = size * 0.35f
        p.style = Paint.Style.FILL
        c.drawRect(spL, size * 0.35f, spR, size * 0.65f, p)
        val path = Path().apply {
            moveTo(spR, size * 0.35f); lineTo(size * 0.55f, size * 0.2f)
            lineTo(size * 0.55f, size * 0.8f); lineTo(spR, size * 0.65f); close()
        }
        c.drawPath(path, p)
        if (on) {
            p.style = Paint.Style.STROKE
            c.drawArc(size * 0.5f, size * 0.3f, size * 0.75f, size * 0.7f, -45f, 90f, false, p)
            c.drawArc(size * 0.55f, size * 0.15f, size * 0.9f, size * 0.85f, -45f, 90f, false, p)
        } else {
            p.style = Paint.Style.STROKE
            c.drawLine(size * 0.65f, size * 0.3f, size * 0.85f, size * 0.7f, p)
            c.drawLine(size * 0.85f, size * 0.3f, size * 0.65f, size * 0.7f, p)
        }
        return bmp
    }

    private fun drawInkIcon(size: Int, on: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (on) WHITE else BLACK
            style = Paint.Style.STROKE
            strokeWidth = size * 0.1f; strokeCap = Paint.Cap.ROUND
        }
        // Droplet shape
        val cx = size / 2f
        val path = Path().apply {
            moveTo(cx, size * 0.1f)
            cubicTo(size * 0.2f, size * 0.5f, size * 0.2f, size * 0.75f, cx, size * 0.9f)
            cubicTo(size * 0.8f, size * 0.75f, size * 0.8f, size * 0.5f, cx, size * 0.1f)
        }
        c.drawPath(path, p)
        return bmp
    }

    private fun drawBrightnessIcon(size: Int, on: Boolean, slashed: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (on) WHITE else BLACK
            style = Paint.Style.STROKE
            strokeWidth = size * 0.1f
        }
        val cx = size / 2f; val cy = size / 2f
        c.drawCircle(cx, cy, size * 0.2f, p)
        // Sun rays
        p.strokeCap = Paint.Cap.ROUND
        val rayIn = size * 0.32f; val rayOut = size * 0.44f
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            c.drawLine(
                cx + (rayIn * Math.cos(angle)).toFloat(), cy + (rayIn * Math.sin(angle)).toFloat(),
                cx + (rayOut * Math.cos(angle)).toFloat(), cy + (rayOut * Math.sin(angle)).toFloat(), p
            )
        }
        // Diagonal slash when off
        if (slashed) {
            p.strokeWidth = size * 0.12f
            c.drawLine(size * 0.1f, size * 0.1f, size * 0.9f, size * 0.9f, p)
        }
        return bmp
    }

    // ─── Settings Grid (AiPaper-style) ────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun buildSettingsGrid(context: Context, dp: Float, borderW: Int): View {
        val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val btAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val cameraMgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }

        // ── Quick toggles, wrapped into rows further down ──
        val iconSize = (22 * dp).toInt()

        data class PillItem(
            val label: String,
            val isOn: Boolean,
            val icon: Bitmap,
            val onTap: () -> Unit,
            val isOnAfterTap: () -> Boolean,
            val drawIconForState: (Boolean) -> Bitmap
        )
        val pills = mutableListOf<PillItem>()

        if (wifiMgr != null) {
            val on = wifiMgr.isWifiEnabled
            pills.add(PillItem("WiFi", on, drawWifiIcon(iconSize, on),
                onTap = {
                    try {
                        @Suppress("DEPRECATION")
                        val ok = wifiMgr.setWifiEnabled(!wifiMgr.isWifiEnabled)
                        if (!ok) context.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        try { context.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    }
                },
                isOnAfterTap = { wifiMgr.isWifiEnabled },
                drawIconForState = { s -> drawWifiIcon(iconSize, s) }
            ))
        }
        if (btAdapter != null) {
            val on = btAdapter.isEnabled
            pills.add(PillItem("BT", on, drawBluetoothIcon(iconSize, on),
                onTap = {
                    try { if (btAdapter.isEnabled) btAdapter.disable() else btAdapter.enable() }
                    catch (_: Exception) {
                        try { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    }
                },
                isOnAfterTap = { btAdapter.isEnabled },
                drawIconForState = { s -> drawBluetoothIcon(iconSize, s) }
            ))
        }
        if (cameraMgr != null) {
            pills.add(PillItem("Light", flashlightOn, drawFlashlightIcon(iconSize, flashlightOn),
                onTap = {
                    try {
                        val cameraId = cameraMgr.cameraIdList.firstOrNull() ?: return@PillItem
                        flashlightOn = !flashlightOn
                        cameraMgr.setTorchMode(cameraId, flashlightOn)
                    } catch (_: Exception) { flashlightOn = false }
                },
                isOnAfterTap = { flashlightOn },
                drawIconForState = { s -> drawFlashlightIcon(iconSize, s) }
            ))
        }

        // Location. Toggling it needs WRITE_SECURE_SETTINGS, which is signature|privileged but
        // carries the "development" flag — so it cannot be requested at runtime, yet CAN be
        // granted over adb:
        //     adb shell pm grant com.kompaktx.app android.permission.WRITE_SECURE_SETTINGS
        // With it, this flips location directly. Without it, the write throws and we fall back to
        // opening the system's location screen, which is the most any ordinary app can do.
        val locationMgr = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        if (showLocationToggle && locationMgr != null) {
            fun locationOn(): Boolean = try { locationMgr.isLocationEnabled } catch (_: Exception) { false }
            pills.add(PillItem("Location", locationOn(), drawLocationIcon(iconSize, locationOn()),
                onTap = {
                    if (!setLocationEnabled(context, locationMgr, !locationOn())) {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {}
                        dismissPanel()
                    }
                },
                isOnAfterTap = { locationOn() },
                drawIconForState = { st -> drawLocationIcon(iconSize, st) }
            ))
        }

        // Four pills across a 56 mm screen leaves each about 13 mm wide, which squashes the
        // longer labels. Wrap to rows of two instead once there are more than three.
        val perRow = if (pills.size > 3) 2 else pills.size.coerceAtLeast(1)
        var currentRow: LinearLayout? = null
        for ((idx, pill) in pills.withIndex()) {
            if (idx % perRow == 0) {
                currentRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { if (idx > 0) topMargin = (8 * dp).toInt() }
                }
                grid.addView(currentRow)
            }
            val iconView = ImageView(context).apply {
                setImageDrawable(BitmapDrawable(context.resources, pill.icon))
                layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()).apply {
                    marginEnd = (6 * dp).toInt()
                }
            }
            val labelView = TextView(context).apply {
                text = pill.label
                setTextColor(if (pill.isOn) WHITE else BLACK)
                textSize = baseSmallSize; typeface = Typeface.DEFAULT_BOLD
            }
            val bg = GradientDrawable().apply {
                cornerRadius = 12 * dp
                if (pill.isOn) setColor(BLACK) else { setColor(WHITE); setStroke(borderW, BLACK) }
            }
            val pillView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = bg
                setPadding((10 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    val posInRow = idx % perRow
                    if (posInRow > 0) marginStart = (4 * dp).toInt()
                    if (posInRow < perRow - 1) marginEnd = (4 * dp).toInt()
                }
                setOnClickListener {
                    tapFeedback(context)
                    pill.onTap()
                    // Instant visual update — swap colors in place, no panel rebuild
                    val nowOn = pill.isOnAfterTap()
                    val newBg = GradientDrawable().apply {
                        cornerRadius = 12 * dp
                        if (nowOn) setColor(BLACK) else { setColor(WHITE); setStroke(borderW, BLACK) }
                    }
                    this.background = newBg
                    labelView.setTextColor(if (nowOn) WHITE else BLACK)
                    val newIcon = pill.drawIconForState(nowOn)
                    iconView.setImageDrawable(BitmapDrawable(context.resources, newIcon))
                }
            }
            pillView.addView(iconView)
            pillView.addView(labelView)
            currentRow?.addView(pillView)
        }

        // Breathing room before the System Settings row below.
        currentRow?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = (10 * dp).toInt() }

        // Straight into Android's own settings — the one destination the panel can't provide
        // itself, and awkward to reach on a device with no app drawer shortcut for it.
        val gearBg = GradientDrawable().apply {
            cornerRadius = 12 * dp; setStroke(borderW, BLACK); setColor(WHITE)
        }
        grid.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = gearBg
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt())
                    .apply { marginEnd = (8 * dp).toInt() }
                setImageDrawable(BitmapDrawable(context.resources, drawGearIcon((20 * dp).toInt())))
            })
            addView(TextView(context).apply {
                text = "System Settings"
                setTextColor(BLACK); textSize = baseSmallSize; typeface = Typeface.DEFAULT_BOLD
            })
            setOnClickListener {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {}
                dismissPanel()
            }
        })
        return grid
    }

    /**
     * Used / total RAM, read straight from ActivityManager. Rendered as a labelled bar so it is
     * legible at a glance on a panel that has no colour to spend on it.
     */
    /**
     * Long-press menu for a recent app: App info, or a best-effort stop.
     *
     * Offers App info and the two hide filters. Stopping an app is deliberately absent — see the
     * comment on the actions below.
     *
     * Drawn inside the panel rather than as a system dialog: an overlay cannot show an AlertDialog
     * without a window of its own, and a second window here would flicker on e-ink.
     */
    private fun showAppActionsDialog(context: Context, dp: Float, pkg: String, label: String) {
        val root = panelView as? FrameLayout ?: return
        val borderW = (BORDER_WIDTH_DP * dp).toInt().coerceAtLeast(2)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(WHITE); setStroke(borderW, BLACK); cornerRadius = 16 * dp
            }
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            isClickable = true  // don't let taps fall through to the scrim behind
        }
        card.addView(TextView(context).apply {
            text = label
            setTextColor(BLACK); textSize = baseTitleSize; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })

        // Wrapper so the whole thing can be removed as one view.
        val holder = FrameLayout(context).apply {
            setBackgroundColor(0x00000000)
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        fun close() {
            try { root.removeView(holder) } catch (_: Exception) {}
            if (appActionsDialog === holder) appActionsDialog = null
        }
        holder.setOnClickListener { close() }

        fun action(text: String, subtitle: String?, onTap: () -> Unit) {
            card.addView(View(context).apply {
                setBackgroundColor(BLACK)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1)
                ).apply { topMargin = (10 * dp).toInt(); bottomMargin = (2 * dp).toInt() }
            })
            card.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = (44 * dp).toInt()
                setPadding(0, (9 * dp).toInt(), 0, (9 * dp).toInt())
                addView(TextView(context).apply {
                    this.text = text
                    setTextColor(BLACK); textSize = baseTextSize + 1f
                    typeface = Typeface.DEFAULT_BOLD
                })
                if (subtitle != null) addView(TextView(context).apply {
                    this.text = subtitle
                    setTextColor(BLACK); textSize = baseTinySize
                })
                setOnClickListener { onTap() }
            })
        }

        fun openAppInfo() {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:$pkg")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {}
        }

        // No stop action. killBackgroundProcesses() exempts the foreground app — the one you most
        // want to stop — and a real force stop needs FORCE_STOP_PACKAGES, which is
        // signature|privileged with no development flag, so unlike WRITE_SECURE_SETTINGS it cannot
        // be granted over adb either. App info is where the button that actually works lives.
        action("App info", "Permissions, storage, force stop") {
            openAppInfo(); close(); dismissPanel()
        }
        action(
            if (hiddenNotiApps.contains(pkg)) "Unhide from notifications" else "Hide from notifications",
            null
        ) {
            val hide = !hiddenNotiApps.contains(pkg)
            managerScope.launch {
                val prefs = com.noti.restore.ui.PreferencesManager.getInstance(context.applicationContext)
                if (hide) prefs.addHiddenNotiApp(pkg) else prefs.removeHiddenNotiApp(pkg)
            }
            close(); refreshPanelIfShowing()
        }
        action(
            if (hiddenRecentApps.contains(pkg)) "Unhide from recents" else "Hide from recents",
            null
        ) {
            val hide = !hiddenRecentApps.contains(pkg)
            managerScope.launch {
                val prefs = com.noti.restore.ui.PreferencesManager.getInstance(context.applicationContext)
                if (hide) prefs.addHiddenRecentApp(pkg) else prefs.removeHiddenRecentApp(pkg)
            }
            close(); refreshPanelIfShowing()
        }
        action("Cancel", null) { close() }

        holder.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            marginStart = (24 * dp).toInt(); marginEnd = (24 * dp).toInt()
        })
        root.addView(holder)
        appActionsDialog = holder
    }

    /** Simple gear glyph, drawn to match the panel's other 1-bit icons. */
    private fun drawGearIcon(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BLACK; style = Paint.Style.STROKE
            strokeWidth = size * 0.11f; strokeCap = Paint.Cap.ROUND
        }
        val cx = size / 2f; val cy = size / 2f
        val rOuter = size * 0.34f
        canvas.drawCircle(cx, cy, size * 0.17f, paint)
        // Eight teeth as short radial spokes.
        for (i in 0 until 8) {
            val a = Math.toRadians((i * 45).toDouble())
            val sx = cx + (rOuter * 0.62f) * Math.cos(a).toFloat()
            val sy = cy + (rOuter * 0.62f) * Math.sin(a).toFloat()
            val ex = cx + rOuter * Math.cos(a).toFloat()
            val ey = cy + rOuter * Math.sin(a).toFloat()
            canvas.drawLine(sx, sy, ex, ey, paint)
        }
        return bmp
    }

    private fun buildRefreshModeCircles(context: Context, dp: Float, borderW: Int): View {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (4 * dp).toInt())
        }
        section.addView(TextView(context).apply {
            text = "Refresh Mode"; setTextColor(BLACK); textSize = baseTinySize
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * dp).toInt() }
        })
        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        for (mode in EinkMode.ALL) {
            val isSelected = MeinkController.currentMode == mode
            val label = EinkMode.label(mode)
            val code = EinkMode.code(mode)
            val btnSize = (44 * dp).toInt()
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (isSelected) setColor(BLACK) else { setColor(WHITE); setStroke(borderW, BLACK) }
            }
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(FrameLayout(context).apply {
                background = bg
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                addView(TextView(context).apply {
                    text = code
                    setTextColor(if (isSelected) WHITE else BLACK)
                    textSize = if (code.length > 2) baseSmallSize - 1f else baseTitleSize
                    typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                    )
                })
                setOnClickListener {
                    tapFeedback(context)
                    MeinkController.setMode(mode)
                    handler.postDelayed({ buildAndShowPanel(context) }, 100)
                }
            })
            col.addView(TextView(context).apply {
                text = label; setTextColor(BLACK); textSize = baseTinySize
                gravity = Gravity.CENTER; maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (3 * dp).toInt(); gravity = Gravity.CENTER_HORIZONTAL }
            })
            modeRow.addView(col)
        }
        section.addView(modeRow)
        return section
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBrightnessSection(context: Context, dp: Float): View {
        val borderW = (BORDER_WIDTH_DP * dp).toInt().coerceAtLeast(1)
        // Horizontal row: sun icon | slider track | percentage
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        }
        val resolver = context.contentResolver
        // Report 0 while the front light is off: Settings.System holds the device's nonzero
        // floor in that state, which would otherwise render as a few percent instead of "Off".
        val currentBrightness = if (isFrontLightOff) 0 else try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { 128 }
        // No mode-forcing here: merely opening the panel to look at the slider must not turn
        // Adaptive Brightness off. Manual mode is taken on an actual drag / sun-icon tap, both of
        // which go through setManualBrightness().

        // Percentage label on right (declare early so sun icon click can update it)
        val valueText = TextView(context).apply {
            text = if (currentBrightness == 0) "Off" else "${currentBrightness * 100 / 255}%"
            setTextColor(BLACK); textSize = baseSmallSize; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * dp).toInt() }
        }

        // Slider — thicker track, larger thumb with white center + black ring
        val trackH = (8 * dp).toInt()
        val thumbSize = (28 * dp).toInt()
        val sliderContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, (36 * dp).toInt(), 1f)
        }

        // Track background (outlined rounded rect)
        val trackBw = (2 * dp).toInt().coerceAtLeast(1)
        sliderContainer.addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(WHITE); setStroke(trackBw, BLACK); cornerRadius = trackH.toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, trackH
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        })

        // Fill bar
        val fill = View(context).apply {
            background = GradientDrawable().apply { setColor(BLACK); cornerRadius = trackH.toFloat() }
        }
        sliderContainer.addView(fill, FrameLayout.LayoutParams(0, trackH).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        })

        // Thumb — white circle with thick black border
        val thumb = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(WHITE)
                setStroke((3 * dp).toInt().coerceAtLeast(2), BLACK)
            }
            layoutParams = FrameLayout.LayoutParams(thumbSize, thumbSize).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        sliderContainer.addView(thumb)

        fun updateSlider(brightness: Int, width: Int) {
            val usable = width - thumbSize
            val frac = brightness / 255f
            val thumbX = (frac * usable).toInt()
            (thumb.layoutParams as FrameLayout.LayoutParams).leftMargin = thumbX; thumb.requestLayout()
            (fill.layoutParams as FrameLayout.LayoutParams).width = thumbX + thumbSize / 2; fill.requestLayout()
            valueText.text = if (brightness == 0) "Off" else "${brightness * 100 / 255}%"
        }

        // Sun icon on left — tap to toggle between 0 (off) and last manual brightness
        val sunSize = (24 * dp).toInt()
        val isOff = currentBrightness <= 0
        val sunIcon = ImageView(context).apply {
            setImageDrawable(BitmapDrawable(context.resources, drawBrightnessIcon(sunSize, false, slashed = isOff)))
            layoutParams = LinearLayout.LayoutParams(sunSize, sunSize).apply {
                marginEnd = (8 * dp).toInt()
            }
        }

        sliderContainer.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            var done = false
            override fun onGlobalLayout() {
                if (done) return; done = true
                sliderContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                updateSlider(currentBrightness, sliderContainer.width)
            }
        })
        sliderContainer.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val frac = ((event.x - thumbSize / 2) / (v.width - thumbSize)).coerceIn(0f, 1f)
                    val b = (frac * 255).toInt().coerceIn(0, 255)
                    if (b > 0) { lastManualBrightness = b; saveBrightness(context) }
                    setManualBrightness(resolver, b)
                    updateSlider(b, v.width)
                    sunIcon.setImageDrawable(BitmapDrawable(context.resources,
                        drawBrightnessIcon(sunSize, false, slashed = b <= 0)))
                    true
                }
                else -> true
            }
        }
        sunIcon.setOnClickListener {
            val target = if (isFrontLightOff) {
                frontLightRestoreValue()
            } else {
                val current = try {
                    Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
                } catch (_: Exception) { 0 }
                if (current > 0) { lastManualBrightness = current; saveBrightness(context) }
                0
            }
            setManualBrightness(resolver, target)
            if (sliderContainer.width > 0) updateSlider(target, sliderContainer.width)
            // Update sun icon slash state
            sunIcon.setImageDrawable(BitmapDrawable(context.resources,
                drawBrightnessIcon(sunSize, false, slashed = target <= 0)))
        }

        // Store live references for external updates (e.g. hardware shortcut toggle)
        liveSunIcon = sunIcon
        liveSunSize = sunSize
        liveSliderContainer = sliderContainer
        liveSliderThumb = thumb
        liveSliderFill = fill
        liveSliderThumbSize = thumbSize
        liveValueText = valueText

        row.addView(sunIcon)
        row.addView(sliderContainer)
        row.addView(valueText)
        return row
    }

    /**
     * "Auto" pill that toggles the system's Adaptive Brightness, styled to match the
     * WiFi/Bluetooth/Flashlight toggles.
     *
     * Only writes SCREEN_BRIGHTNESS_MODE. Everything that follows — releasing our window override
     * so the OS's ambient curve can drive the panel, or reclaiming it when switching back to
     * manual — is handled by the mode ContentObserver landing in syncBrightnessOverrides().
     *
     * Dragging the brightness slider while this is on still switches back to manual, matching what
     * stock Android does when you drag its slider.
     */
    private fun buildAdaptiveBrightnessToggle(context: Context, dp: Float, borderW: Int): View {
        val resolver = context.contentResolver
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * dp).toInt(), 0, (6 * dp).toInt())
        }

        fun bgFor(on: Boolean) = GradientDrawable().apply {
            cornerRadius = 12 * dp
            if (on) setColor(BLACK) else { setColor(WHITE); setStroke(borderW, BLACK) }
        }

        val startOn = isAdaptiveBrightnessOn(resolver)
        val label = TextView(context).apply {
            text = "Auto"
            setTextColor(if (startOn) WHITE else BLACK)
            textSize = baseSmallSize; typeface = Typeface.DEFAULT_BOLD
        }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = bgFor(startOn)
            // Kept so the pill can be refreshed in place — dragging the brightness slider takes
            // the system out of adaptive mode, and rebuilding the whole panel to reflect that
            // would be a full e-ink redraw mid-drag.
            liveAdaptivePill = this
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            setOnClickListener {
                tapFeedback(context)
                val turningOn = !isAdaptiveBrightnessOn(resolver)
                try {
                    Settings.System.putInt(
                        resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        if (turningOn) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                } catch (_: Exception) {}
                // Re-read rather than trusting the write: without WRITE_SETTINGS granted it is a
                // no-op, and the pill must not claim a state the system didn't take.
                val nowOn = isAdaptiveBrightnessOn(resolver)
                background = bgFor(nowOn)
                label.setTextColor(if (nowOn) WHITE else BLACK)
                syncBrightnessOverrides(context, force = true)
            }
        }
        pill.addView(label)
        liveAdaptiveLabel = label
        liveAdaptiveBg = { on -> bgFor(on) }

        row.addView(TextView(context).apply {
            text = "Adaptive brightness"
            setTextColor(BLACK); textSize = baseSmallSize
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(pill)
        return row
    }

    // ─── E-Ink Controls Section (legacy, kept for reference) ────────

    private fun buildEinkControlsSection(context: Context, dp: Float): View {
        val borderW = (BORDER_WIDTH_DP * dp).toInt().coerceAtLeast(1)

        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (4 * dp).toInt())
        }

        section.addView(TextView(context).apply {
            text = "Refresh Mode"
            setTextColor(BLACK); textSize = baseTinySize; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * dp).toInt() }
        })

        val buttonsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val buttonViews = mutableListOf<TextView>()

        for (mode in EinkMode.ALL) {
            val btn = TextView(context).apply {
                text = EinkMode.label(mode)
                textSize = baseSmallSize
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (3 * dp).toInt(); marginEnd = (3 * dp).toInt()
                }
            }
            buttonViews.add(btn)
            buttonsRow.addView(btn)
        }

        // Apply initial state and click handlers
        fun updateEinkButtonStates() {
            EinkMode.ALL.forEachIndexed { idx, mode ->
                val isSelected = MeinkController.currentMode == mode
                val btnBg = GradientDrawable().apply {
                    cornerRadius = 100 * dp
                    if (isSelected) setColor(BLACK)
                    else { setColor(WHITE); setStroke(borderW, BLACK) }
                }
                buttonViews[idx].apply {
                    setTextColor(if (isSelected) WHITE else BLACK)
                    background = btnBg
                }
            }
        }

        EinkMode.ALL.forEachIndexed { idx, mode ->
            buttonViews[idx].setOnClickListener {
                MeinkController.setMode(mode)
                updateEinkButtonStates()
            }
        }

        updateEinkButtonStates()

        section.addView(buttonsRow)
        return section
    }

    // ─── Media Widget ───────────────────────────────────────────────

    private fun buildMediaWidget(context: Context, dp: Float, media: com.noti.restore.service.ExternalMediaInfo): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val noteIconSize = (24 * dp).toInt()
        row.addView(ImageView(context).apply {
            setImageDrawable(BitmapDrawable(context.resources, drawMusicNoteIcon(noteIconSize)))
            layoutParams = LinearLayout.LayoutParams(noteIconSize, noteIconSize).apply {
                marginEnd = (8 * dp).toInt()
            }
        })
        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoCol.addView(TextView(context).apply {
            text = media.title ?: "Unknown"; setTextColor(BLACK); textSize = baseTitleSize
            typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (media.artist != null) {
            infoCol.addView(TextView(context).apply {
                text = media.artist; setTextColor(BLACK); textSize = baseSmallSize
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(infoCol)

        // Tap song info to open the music app
        val openMusicApp = View.OnClickListener {
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(media.packageName)
                    ?: Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_APP_MUSIC)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                dismissPanel()
            } catch (_: Exception) {}
        }
        infoCol.setOnClickListener(openMusicApp)

        val btnSize = (32 * dp).toInt()
        val borderW = (BORDER_WIDTH_DP * dp).toInt().coerceAtLeast(1)
        val iconInner = (btnSize * 0.5f).toInt()
        row.addView(makeMediaIconButton(context, dp, btnSize, borderW, drawPrevIcon(iconInner)) { KompaktXListener.previousExternalMedia() })
        row.addView(makeMediaIconButton(context, dp, btnSize, borderW,
            if (media.isPlaying) drawPauseIcon(iconInner) else drawPlayIcon(iconInner)
        ) { KompaktXListener.playPauseExternalMedia() })
        row.addView(makeMediaIconButton(context, dp, btnSize, borderW, drawNextIcon(iconInner)) { KompaktXListener.nextExternalMedia() })
        return row
    }

    private fun makeMediaIconButton(context: Context, dp: Float, size: Int, borderW: Int, icon: Bitmap, onClick: () -> Unit): View {
        val bg = GradientDrawable().apply { setColor(WHITE); setStroke(borderW, BLACK); cornerRadius = 6 * dp }
        return ImageView(context).apply {
            setImageDrawable(BitmapDrawable(context.resources, icon))
            scaleType = ImageView.ScaleType.CENTER
            background = bg
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = (3 * dp).toInt() }
            setOnClickListener { onClick() }
        }
    }

    // ─── Recent Apps ────────────────────────────────────────────────

    private fun buildRecentAppsRow(context: Context, dp: Float): View {
        val recentApps = getRecentApps(context, 5)
        if (recentApps.isEmpty()) {
            return TextView(context).apply {
                text = "Grant Usage Access in Settings"; setTextColor(BLACK); textSize = baseSmallSize
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        recentApps.forEach { appInfo ->
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
                // Equal weight so all items share the width evenly
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnLongClickListener {
                    showAppActionsDialog(context, dp, appInfo.packageName, stripEmojis(appInfo.label))
                    true
                }
                setOnClickListener {
                    try {
                        // Try CATEGORY_LAUNCHER first
                        val li = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            setPackage(appInfo.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val resolveInfo = context.packageManager.queryIntentActivities(li, 0).firstOrNull()
                        if (resolveInfo != null) {
                            li.setClassName(appInfo.packageName, resolveInfo.activityInfo.name)
                            context.startActivity(li)
                            dismissPanel()
                        } else {
                            // Fallback: try ACTION_MAIN without category
                            val fallback = Intent(Intent.ACTION_MAIN).apply {
                                setPackage(appInfo.packageName)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            val fi = context.packageManager.queryIntentActivities(fallback, 0)
                                .firstOrNull { it.activityInfo.exported }
                            if (fi != null) {
                                fallback.setClassName(appInfo.packageName, fi.activityInfo.name)
                                context.startActivity(fallback)
                                dismissPanel()
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            val iconSize = (40 * dp).toInt()
            if (hideAppIcons) {
                item.addView(buildLetterBadge(context, dp, appInfo.label, iconSize))
            } else {
                item.addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                    try { setImageDrawable(context.packageManager.getApplicationIcon(appInfo.packageName)) } catch (_: Exception) {}
                })
            }
            item.addView(TextView(context).apply {
                text = stripEmojis(appInfo.label); setTextColor(BLACK); textSize = baseTinySize; gravity = Gravity.CENTER
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (2 * dp).toInt() }
            })
            row.addView(item)
        }
        return row
    }

    /** Vertical list of recent apps for the dedicated tab (TABBED layout).
     *  Text-only rows when [hideAppIcons] is on; otherwise icon + label.
     *  Last-opened time is shown on the right. No dividers between rows. */
    private fun buildRecentsListContent(context: Context, dp: Float): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
        }
        val recentApps = getRecentApps(context, 30)
        if (recentApps.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "Grant Usage Access in Settings"
                setTextColor(BLACK); textSize = baseTitleSize; gravity = Gravity.CENTER
                setPadding(0, (24 * dp).toInt(), 0, (24 * dp).toInt())
            })
            return container
        }
        recentApps.forEach { appInfo ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { launchApp(context, appInfo.packageName) }
                setOnLongClickListener {
                    showAppActionsDialog(context, dp, appInfo.packageName, stripEmojis(appInfo.label))
                    true
                }
            }
            // Icon (only when icons are not hidden — text-only mode otherwise)
            if (!hideAppIcons) {
                val iconSize = (32 * dp).toInt()
                row.addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        marginEnd = (12 * dp).toInt()
                    }
                    try { setImageDrawable(context.packageManager.getApplicationIcon(appInfo.packageName)) } catch (_: Exception) {}
                })
            }
            row.addView(TextView(context).apply {
                text = stripEmojis(appInfo.label); setTextColor(BLACK); textSize = baseTextSize + 1f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(context).apply {
                text = formatTime(appInfo.lastUsed); setTextColor(BLACK); textSize = baseTinySize
                setPadding((8 * dp).toInt(), 0, 0, 0)
            })
            container.addView(row)
        }
        return container
    }

    /** Bordered first-letter circle used in place of the app icon when icons are hidden. */
    private fun buildLetterBadge(context: Context, dp: Float, label: String, sizePx: Int): View {
        val borderW = (BORDER_WIDTH_DP * dp).toInt().coerceAtLeast(1)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(WHITE); setStroke(borderW, BLACK)
        }
        val letter = label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
        return TextView(context).apply {
            text = letter
            setTextColor(BLACK); typeface = Typeface.DEFAULT_BOLD
            textSize = (sizePx / dp) * 0.42f
            gravity = Gravity.CENTER
            background = bg
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
    }

    /** Launch an app by package name from a recents entry. Dismisses the panel on success. */
    /**
     * Short tick on a deliberate press. Uses the Vibrator directly rather than
     * performHapticFeedback, which is silently dropped when a view has haptics disabled or the
     * window is not focusable — and the panel window deliberately is not.
     */
    private var clockUpdateRunnable: Runnable? = null

    /** Keep the header's day/time/battery line current while the panel stays open. */
    private fun startClockUpdates(context: Context, line: TextView) {
        stopClockUpdates()
        val runnable = object : Runnable {
            override fun run() {
                if (panelView == null) { stopClockUpdates(); return }
                val cal = java.util.Calendar.getInstance()
                val battery = try {
                    context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
                        val lvl = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        if (lvl >= 0 && scale > 0) lvl * 100 / scale else null
                    }
                } catch (_: Exception) { null }
                line.text = buildString {
                    append(android.text.format.DateFormat.format("EEEE", cal))
                    append("  |  ")
                    append(android.text.format.DateFormat.getTimeFormat(context).format(cal.time))
                    if (battery != null) { append("  |  "); append("$battery%") }
                }
                handler.postDelayed(this, CLOCK_REFRESH_MS)
            }
        }
        clockUpdateRunnable = runnable
        handler.postDelayed(runnable, CLOCK_REFRESH_MS)
    }

    private fun stopClockUpdates() {
        clockUpdateRunnable?.let { handler.removeCallbacks(it) }
        clockUpdateRunnable = null
    }

    private fun tapFeedback(context: Context) {
        if (!hapticsEnabled) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return
            // This vibrator reports no amplitude-control capability, so createOneShot's amplitude
            // is meaningless and a very short one-shot produces nothing you can feel. It does
            // advertise the predefined effects, so use those — EFFECT_TICK for a light press,
            // falling back to a longer plain buzz on hardware without them.
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            } else {
                VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator.vibrate(effect)
        } catch (_: Exception) {}
    }

    private fun launchApp(context: Context, pkg: String) {
        try {
            val li = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val ri = context.packageManager.queryIntentActivities(li, 0).firstOrNull()
            if (ri != null) {
                li.setClassName(pkg, ri.activityInfo.name)
                context.startActivity(li); dismissPanel(); return
            }
            val fallback = Intent(Intent.ACTION_MAIN).apply {
                setPackage(pkg); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val fi = context.packageManager.queryIntentActivities(fallback, 0)
                .firstOrNull { it.activityInfo.exported }
            if (fi != null) {
                fallback.setClassName(pkg, fi.activityInfo.name)
                context.startActivity(fallback); dismissPanel()
            }
        } catch (_: Exception) {}
    }

    data class RecentAppInfo(val packageName: String, val label: String, val lastUsed: Long)

    private fun getRecentApps(context: Context, count: Int): List<RecentAppInfo> {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: run {
                Log.e(TAG, "UsageStatsManager is null")
                return emptyList()
            }
            val end = System.currentTimeMillis()
            val own = context.packageName
            val pm = context.packageManager
            val merged = mutableMapOf<String, Long>() // pkg -> lastUsed timestamp

            // 1) queryEvents for last 7 days
            try {
                val events = usm.queryEvents(end - 7 * 86400000L, end)
                val event = android.app.usage.UsageEvents.Event()
                var eventCount = 0
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    eventCount++
                    if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ||
                        @Suppress("DEPRECATION") event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        val pkg = event.packageName
                        if (pkg != own && pkg !in hiddenRecentApps) {
                            val prev = merged[pkg] ?: 0L
                            if (event.timeStamp > prev) merged[pkg] = event.timeStamp
                        }
                    }
                }
                Log.e(TAG, "queryEvents: $eventCount total events, ${merged.size} unique foreground packages")
            } catch (e: Exception) { Log.e(TAG, "queryEvents failed", e) }

            // 2) queryUsageStats with multiple intervals
            val intervals = intArrayOf(
                UsageStatsManager.INTERVAL_DAILY,
                UsageStatsManager.INTERVAL_WEEKLY,
                UsageStatsManager.INTERVAL_MONTHLY,
                UsageStatsManager.INTERVAL_BEST
            )
            val ranges = longArrayOf(
                7 * 86400000L,
                30 * 86400000L,
                90 * 86400000L,
                90 * 86400000L
            )
            val intervalNames = arrayOf("DAILY", "WEEKLY", "MONTHLY", "BEST")
            for (i in intervals.indices) {
                try {
                    val stats = usm.queryUsageStats(intervals[i], end - ranges[i], end)
                    val beforeSize = merged.size
                    stats?.forEach { stat ->
                        if (stat.packageName != own && stat.packageName !in hiddenRecentApps) {
                            // Accept any app with foreground time OR a recent lastTimeUsed
                            if (stat.totalTimeInForeground > 0 || stat.lastTimeUsed > end - ranges[i]) {
                                val prev = merged[stat.packageName] ?: 0L
                                if (stat.lastTimeUsed > prev) merged[stat.packageName] = stat.lastTimeUsed
                            }
                        }
                    }
                    Log.e(TAG, "queryUsageStats ${intervalNames[i]}: ${stats?.size ?: 0} entries, added ${merged.size - beforeSize} new pkgs")
                } catch (e: Exception) { Log.e(TAG, "queryUsageStats ${intervalNames[i]} failed", e) }
            }

            Log.e(TAG, "Recent apps total: ${merged.size} packages. Top 15: ${
                merged.entries.sortedByDescending { it.value }.take(15).joinToString { it.key.substringAfterLast('.') }
            }")

            // Build a set of all launchable packages using multiple methods
            val launchablePackages = mutableSetOf<String>()
            try {
                // Method 1: CATEGORY_LAUNCHER (standard)
                val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(mainIntent, 0).forEach { ri ->
                    launchablePackages.add(ri.activityInfo.packageName)
                }
                Log.e(TAG, "CATEGORY_LAUNCHER: ${launchablePackages.size} packages")

                // Method 2: CATEGORY_LEANBACK_LAUNCHER (some devices use this)
                try {
                    val leanIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    pm.queryIntentActivities(leanIntent, 0).forEach { ri ->
                        launchablePackages.add(ri.activityInfo.packageName)
                    }
                } catch (_: Exception) {}

                // Method 3: Check each usage-tracked package individually
                // This catches apps the above queries miss on custom launchers
                for (pkg in merged.keys) {
                    if (pkg in launchablePackages) continue
                    try {
                        val pkgIntent = Intent(Intent.ACTION_MAIN, null)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setPackage(pkg)
                        if (pm.queryIntentActivities(pkgIntent, 0).isNotEmpty()) {
                            launchablePackages.add(pkg)
                            continue
                        }
                        // Try without category as last resort
                        val plainIntent = Intent(Intent.ACTION_MAIN, null).setPackage(pkg)
                        val activities = pm.queryIntentActivities(plainIntent, 0)
                        if (activities.any { it.activityInfo.exported }) {
                            launchablePackages.add(pkg)
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            Log.e(TAG, "Launchable packages total: ${launchablePackages.size}")

            // Resolve to launchable apps
            val result = merged.entries
                .sortedByDescending { it.value }
                .mapNotNull { (pkg, time) ->
                    try {
                        if (pkg !in launchablePackages) return@mapNotNull null
                        val ai = pm.getApplicationInfo(pkg, 0)
                        RecentAppInfo(pkg, pm.getApplicationLabel(ai).toString(), time)
                    } catch (e: Exception) { null }
                }
                .take(count)

            Log.e(TAG, "Recent apps final: ${result.size} launchable. Names: ${result.joinToString { it.label }}")

            return result
        } catch (e: Exception) { Log.e(TAG, "Failed to get recent apps", e); return emptyList() }
    }

    // ─── Heads-Up Popup ─────────────────────────────────────────────

    fun showHeadsUpNotification(context: Context, data: NotificationPopupData) {
        if (!headsUpEnabled) return
        // Suppress popups when tray is open
        if (isPanelShowing) return
        if (!android.provider.Settings.canDrawOverlays(context)) return
        handler.post {
            // A repeat of something already showing or waiting replaces that entry rather than
            // appearing twice — messaging apps re-post the same key on every new message.
            headsUpQueue.removeAll { it.key == data.key }
            // Newest wins, as Android's own heads-up does — with one exception: never tear down a
            // reply being typed. That popup owns a focused field and unsaved text, so the arriving
            // notification waits and is shown once the reply is done.
            val busy = headsUpShowingKey != null && headsUpShowingKey != data.key
            if (busy && replyBoxOpen) {
                headsUpQueue.addLast(data)
                while (headsUpQueue.size > HEADS_UP_QUEUE_MAX) headsUpQueue.removeFirst()
                return@post
            }
            showHeadsUpNow(context, data)
        }
    }

    /**
     * Put a popup on screen immediately, replacing whatever is there.
     *
     * Runs directly on the handler thread rather than posting again: callers already post, and the
     * extra hop meant headsUpShowingKey was still unset when the next notification of the same
     * batch tested it — so a burst all decided nothing was showing and skipped the queue.
     */
    private fun showHeadsUpNow(context: Context, data: NotificationPopupData) {
        run {
            removeHeadsUp(advanceQueue = false); replyBoxOpen = false
            headsUpShowingKey = data.key
            try {
                if (windowManager == null) windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val dp = context.resources.displayMetrics.density
                val statusBarHeight = getStatusBarHeight(context)
                val view = buildHeadsUpView(context, dp, data)
                headsUpView = view
                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 0 }
                headsUpParams = layoutParams
                view.setOnTouchListener(createHeadsUpTouchListener(context, data))
                // Prefer TYPE_ACCESSIBILITY_OVERLAY, which is the only one of the two window types
                // that draws *above* the status bar.
                headsUpUsingAccessibility =
                    com.noti.restore.service.RecentsButtonService.addHeadsUp(view, layoutParams)
                if (!headsUpUsingAccessibility) {
                    // Fallback path, taken whenever the accessibility service isn't connected —
                    // not yet enabled, killed and awaiting restart, or restarting after an update.
                    // A plain overlay is layered BENEATH the status bar, so at y = 0 with
                    // FLAG_LAYOUT_NO_LIMITS the pop-up renders behind it and looks clipped or
                    // missing. This is why the problem appears random across users and can "fix
                    // itself": it tracks the accessibility service's connection state, not the
                    // notification. Push the pop-up below the status bar instead.
                    //
                    // addHeadsUp() may also have already switched the shared params over to the
                    // accessibility type before failing, which a non-accessibility window cannot
                    // use — so put the type back before retrying here.
                    layoutParams.type = windowType
                    layoutParams.y = statusBarHeight
                    windowManager?.addView(view, layoutParams)
                }
                startDismissTimer()
            } catch (e: Exception) { Log.e(TAG, "Failed to show heads-up", e) }
        }
    }

    private fun buildHeadsUpView(context: Context, dp: Float, data: NotificationPopupData): View {
        val pad = (14 * dp).toInt(); val padSmall = (8 * dp).toInt()
        val borderW = (BORDER_WIDTH_DP * dp).toInt().coerceAtLeast(2)
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), 0)
        }
        val cardBg = GradientDrawable().apply { setColor(WHITE); setStroke(borderW, BLACK); cornerRadius = 18 * dp }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); background = cardBg
        }
        val headerRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (!hideAppIcons) {
            val iconSize = (28 * dp).toInt()
            headerRow.addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = padSmall }
                try { setImageDrawable(context.packageManager.getApplicationIcon(data.packageName)) } catch (_: Exception) {}
            })
        }
        headerRow.addView(TextView(context).apply {
            text = data.title; setTextColor(BLACK); textSize = baseTitleSize + 3f; maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(TextView(context).apply {
            text = "\u2715"; setTextColor(BLACK); textSize = baseTitleSize + 4f; setPadding((4 * dp).toInt(), 0, 0, 0)
            setOnClickListener { KompaktXListener.dismiss(data.key); removeHeadsUp() }
        })
        card.addView(headerRow)
        if (data.text.isNotBlank()) {
            val body = TextView(context).apply {
                text = data.text; setTextColor(BLACK); textSize = baseTextSize + 2f; maxLines = 3
                ellipsize = TextUtils.TruncateAt.END; setPadding(0, (4 * dp).toInt(), 0, 0)
            }
            card.addView(body)
            run {
                // Only offered once the text is known to be clipped — measuring after layout is
                // the only way to tell, since three lines of a narrow card is not a character
                // count. Expanding also stops the dismiss timer: you asked to read it.
                val more = TextView(context).apply {
                    text = "Show more"
                    setTextColor(BLACK); textSize = baseTinySize; typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
                    visibility = View.GONE
                    minHeight = (32 * dp).toInt()
                }
                card.addView(more)
                body.viewTreeObserver.addOnGlobalLayoutListener(
                    object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            body.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            val clipped = body.layout?.let { l ->
                                l.lineCount > 0 && l.getEllipsisCount(l.lineCount - 1) > 0
                            } ?: false
                            if (clipped) more.visibility = View.VISIBLE
                        }
                    })
                more.setOnClickListener {
                    tapFeedback(context)
                    if (body.maxLines == 3) {
                        body.maxLines = Int.MAX_VALUE
                        more.text = "Show less"
                        cancelDismissTimer()
                    } else {
                        body.maxLines = 3
                        more.text = "Show more"
                        resetDismissTimer()
                    }
                }
            }
        }
        // Reply
        val replyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, padSmall, 0, 0); visibility = View.GONE
        }
        val replyInput = EditText(context).apply {
            hint = "Reply..."; setHintTextColor(BLACK); setTextColor(BLACK); textSize = baseTextSize; setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val ibg = GradientDrawable().apply { setColor(WHITE); setStroke(borderW, BLACK); cornerRadius = 8 * dp }
            background = ibg; setPadding(padSmall, (6 * dp).toInt(), padSmall, (6 * dp).toInt())
            if (replyOnEnter) {
                // A hardware Enter and the IME's own send/done action both count. Declaring the
                // action lets a soft keyboard show a send key rather than a newline key.
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                setOnEditorActionListener { _, actionId, event ->
                    val enterPressed = event != null &&
                        event.keyCode == KeyEvent.KEYCODE_ENTER &&
                        event.action == KeyEvent.ACTION_DOWN
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || enterPressed) {
                        sendReplyNow?.invoke(); true
                    } else false
                }
            }
        }
        replyContainer.addView(replyInput)
        sendReplyNow = {
            val rt = replyInput.text.toString()
            if (rt.isNotBlank()) {
                data.replyAction?.let { a ->
                    if (KompaktXListener.sendReply(a, rt)) {
                        replyInput.setText("")
                        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .hideSoftInputFromWindow(replyInput.windowToken, 0)
                        setHeadsUpFocusable(false); replyBoxOpen = false; replyContainer.visibility = View.GONE
                        card.addView(TextView(context).apply {
                            setText("Sent"); setTextColor(BLACK); textSize = baseSmallSize; gravity = Gravity.CENTER
                        })
                        handler.postDelayed({ removeHeadsUp() }, 1500)
                    }
                }
            }
        }
        val sendBtnBg = GradientDrawable().apply { setColor(BLACK); cornerRadius = 8 * dp }
        replyContainer.addView(TextView(context).apply {
            text = "\u27A4"; setTextColor(WHITE); textSize = baseTitleSize + 2f; gravity = Gravity.CENTER; background = sendBtnBg
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply { marginStart = padSmall }
            setOnClickListener { sendReplyNow?.invoke() }
        })
        card.addView(replyContainer)
        // Actions
        val actions = data.actions.take(3)
        if (actions.isNotEmpty()) {
            val actionsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, padSmall, 0, 0)
            }
            actions.forEach { na ->
                val bbg = GradientDrawable().apply { setColor(WHITE); setStroke(borderW, BLACK); cornerRadius = 8 * dp }
                actionsRow.addView(TextView(context).apply {
                    text = na.title.uppercase(); setTextColor(BLACK); textSize = baseTextSize; typeface = Typeface.DEFAULT_BOLD
                    setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt()); background = bbg
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = (4 * dp).toInt() }
                    setOnClickListener {
                        if (na.isReply) {
                            replyBoxOpen = true; cancelDismissTimer(); replyContainer.visibility = View.VISIBLE
                            actionsRow.visibility = View.GONE; setHeadsUpFocusable(true); replyInput.requestFocus()
                            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                .showSoftInput(replyInput, InputMethodManager.SHOW_IMPLICIT)
                        } else {
                            try { KompaktXListener.executeAction(na) } catch (_: Exception) {}
                            removeHeadsUp()
                        }
                    }
                })
            }
            card.addView(actionsRow)
        }
        wrapper.addView(card)
        return wrapper
    }

    private fun createHeadsUpTouchListener(context: Context, data: NotificationPopupData) = View.OnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_OUTSIDE -> { if (!replyBoxOpen) resetDismissTimer(); false }
            MotionEvent.ACTION_DOWN -> {
                initialY = (headsUpView?.layoutParams as? WindowManager.LayoutParams)?.y?.toFloat() ?: 0f
                initialTouchY = event.rawY; if (!replyBoxOpen) cancelDismissTimer(); true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!replyBoxOpen) {
                    val dy = event.rawY - initialTouchY
                    if (dy < 0) {
                        val p = v.layoutParams as WindowManager.LayoutParams
                        p.y = (initialY + dy).toInt().coerceAtMost(0)
                        headsUpUpdate(v, p)
                    }
                }; true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!replyBoxOpen) {
                    val dy = event.rawY - initialTouchY
                    if (dy < -100) removeHeadsUp()
                    else if (Math.abs(dy) < 10) {
                        KompaktXListener.openNotification(context, data.key, data.sbn.notification.contentIntent, data.packageName)
                        removeHeadsUp()
                    } else {
                        val p = v.layoutParams as WindowManager.LayoutParams
                        p.y = 0; headsUpUpdate(v, p); startDismissTimer()
                    }
                }; true
            }
            else -> false
        }
    }

    private fun headsUpUpdate(v: android.view.View, p: WindowManager.LayoutParams) {
        if (headsUpUsingAccessibility) com.noti.restore.service.RecentsButtonService.updateHeadsUp(v, p)
        else try { windowManager?.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    private fun setHeadsUpFocusable(focusable: Boolean) {
        val p = headsUpParams ?: return; val v = headsUpView ?: return
        p.flags = if (focusable) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        headsUpUpdate(v, p)
    }

    private fun removeHeadsUp(advanceQueue: Boolean = true) {
        cancelDismissTimer(); replyBoxOpen = false
        headsUpView?.let {
            if (headsUpUsingAccessibility) com.noti.restore.service.RecentsButtonService.removeHeadsUp(it)
            else try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        headsUpView = null; headsUpParams = null; headsUpUsingAccessibility = false
        headsUpShowingKey = null
        if (advanceQueue) {
            val next = headsUpQueue.removeFirstOrNull()
            if (next != null && !isPanelShowing) {
                stripContext?.let { ctx -> handler.post { showHeadsUpNow(ctx, next) } }
            }
        }
    }

    private fun startDismissTimer() {
        cancelDismissTimer()
        // Zero means stay until dismissed — no timer at all, rather than a very long one, so
        // nothing is left pending to fire after the popup has already been swiped away.
        if (headsUpDurationMs <= 0L) return
        dismissRunnable = Runnable { removeHeadsUp() }
        handler.postDelayed(dismissRunnable!!, headsUpDurationMs)
    }
    private fun resetDismissTimer() { if (!replyBoxOpen) startDismissTimer() }
    private fun cancelDismissTimer() { dismissRunnable?.let { handler.removeCallbacks(it) }; dismissRunnable = null }

    // ─── Helpers ────────────────────────────────────────────────────

    /**
     * The single write path for brightness. Call this ONLY from a direct user gesture — a slider
     * drag, a sun-icon tap, the front-light shortcut. It takes ownership of the backlight by
     * switching the system to manual mode, exactly like dragging stock Android's own slider does,
     * and publishes the value to Settings.System so every other app's brightness UI agrees with us.
     *
     * Everything else (screen wake, service reconnect, panel open, an external app's slider) must
     * go through [syncBrightnessOverrides] instead, which only mirrors and never writes.
     */
    private fun setManualBrightness(resolver: android.content.ContentResolver, brightness: Int) {
        val clamped = brightness.coerceIn(0, 255)
        currentBrightnessTarget = clamped
        try {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
        } catch (_: Exception) {}
        frontLightOff = clamped <= 0
        saveBrightness(stripContext)
        // Push it to the physical screen now rather than waiting for the observer, so a drag
        // feels immediate; the observer callback that follows is idempotent and costs nothing.
        applyOverrideToAllSurfaces(if (clamped <= 0) 0.001f else clamped / 255f, force = true)
    }

    /**
     * Mirror the system's current brightness state onto every window override KompaktX holds.
     *
     * This is the heart of the brightness model: the override we hold is a pure function of the
     * two system settings, and this function is the only thing that computes it.
     *
     *   Adaptive Brightness ON  → override = BRIGHTNESS_OVERRIDE_NONE (-1), i.e. "no opinion",
     *                             which hands the backlight back to the OS's ambient-light curve.
     *   Adaptive Brightness OFF → override = SCREEN_BRIGHTNESS / 255, i.e. mirror the system value
     *                             so that whatever wrote it (us, the stock slider, another app)
     *                             actually reaches the panel.
     *
     * It reads state and never writes it, so it can be called from anywhere, as often as we like,
     * without fighting the system or looping through its own ContentObserver.
     */
    private fun syncBrightnessOverrides(context: Context, force: Boolean = false) {
        val resolver = context.contentResolver
        updateAdaptivePillUI(isAdaptiveBrightnessOn(resolver))
        if (isAdaptiveBrightnessOn(resolver)) {
            // The OS owns the backlight now, which necessarily ends an "off" state.
            if (frontLightOff) {
                frontLightOff = false
                saveBrightness(context)
            }
            applyOverrideToAllSurfaces(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, force)
            // Adaptive brightness moves the system value continuously as ambient light changes.
            // Track it in an open panel so the slider follows along live, instead of showing
            // whatever it happened to read when the panel was built.
            try {
                updateBrightnessUI(
                    Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
                )
            } catch (_: Exception) {}
            return
        }
        val sys = try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { return }

        if (frontLightOff) {
            if (sys > 0 && !force && !pendingWakeRestore) {
                // Ambiguous: a nonzero value while we believe the light is off is either the OS
                // restoring brightness as the screen comes back (its write reaches this observer
                // *before* the SCREEN_ON broadcast reaches us, so we cannot yet know) or someone
                // genuinely raising brightness in another app.
                //
                // Don't decide now — assume off and schedule the release. A SCREEN_ON arriving in
                // the meantime cancels it, proving it was the wake restore. Deferring this way
                // wins the race no matter which event lands first, which arming a flag on either
                // broadcast alone did not.
                scheduleOffStateRelease(context, sys)
            }
            // Re-publish the intent so other apps' brightness UIs still show off, and re-assert
            // the sub-minimum override that is what actually darkens the panel. Always forced:
            // after a wake the OS has changed the applied brightness underneath us, so the
            // unchanged-value short-circuit must not suppress this.
            if (sys > 0) {
                try {
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 0)
                } catch (_: Exception) {}
            }
            currentBrightnessTarget = 0
            applyOverrideToAllSurfaces(0.001f, force = true)
            return
        }

        // Adopt the system value as our own, so the sun-icon toggle and a later re-apply resume
        // from what the user last saw rather than from a stale KompaktX-internal number. Only
        // persist when it actually moved — this runs on every drag event via the observer.
        if (sys != currentBrightnessTarget) {
            currentBrightnessTarget = sys
            if (sys > 0) lastManualBrightness = sys
            saveBrightness(context)
        }
        applyOverrideToAllSurfaces(if (sys <= 0) 0.001f else sys / 255f, force)
        // Keep an open panel's slider in step with whoever changed the brightness — the stock
        // System UI slider, another app, or our own sub-panel.
        updateBrightnessUI(sys)
    }

    /**
     * Apply one screenBrightness value to every surface that can own the backlight: the persistent
     * 1x1 overlay, the panel window while it is open, and the accessibility overlay that is the
     * only one of the three visible above the lockscreen.
     *
     * The accessibility overlay is updated, never torn down — that window existing and being
     * refreshed on each wake/reconnect is what makes brightness correct on the lock screen at all
     * on this device. Removing it (rather than setting it to -1) reopens the old "brightness is
     * wrong until you unlock" bug. It is only truly removed in [removeTouchStrip], when the whole
     * overlay subsystem goes away.
     *
     * @param force re-apply even if the value is unchanged. Required whenever a *window* may be
     * new rather than the value — panel opened, accessibility service reconnected, touch strip
     * recreated — because a freshly created window starts with no override of its own. Left false
     * for ContentObserver-driven syncs, which fire on every drag event and would otherwise churn
     * three window updates per motion event on a slow e-ink panel.
     */
    private fun applyOverrideToAllSurfaces(screenValue: Float, force: Boolean = false) {
        if (!force && screenValue == lastAppliedOverride) return
        lastAppliedOverride = screenValue
        applyScreenBrightnessToView(panelView, screenValue)
        applyScreenBrightnessToView(brightnessOverlay, screenValue)
        com.noti.restore.service.RecentsButtonService.applyAccessibilityBrightness(screenValue)
    }

    private fun applyScreenBrightnessToView(view: View?, brightness: Float) {
        view ?: return
        try {
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
            lp.screenBrightness = brightness
            windowManager?.updateViewLayout(view, lp)
        } catch (_: Exception) {}
    }

    /** Repaint the panel's Auto pill to match the system, without rebuilding the panel. */
    private fun updateAdaptivePillUI(on: Boolean) {
        val pill = liveAdaptivePill ?: return
        handler.post {
            liveAdaptiveBg?.let { pill.background = it(on) }
            liveAdaptiveLabel?.setTextColor(if (on) WHITE else BLACK)
        }
    }

    /** True when the system's own Adaptive Brightness is currently on. */
    private fun isAdaptiveBrightnessOn(resolver: android.content.ContentResolver): Boolean {
        return try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (_: Exception) { false }
    }

    /**
     * Re-assert our overrides after a screen wake or an accessibility-service reconnect (which
     * includes the reconnect right after an unlock). Deliberately a mirror pass, not a write: a
     * wake is not the user touching a brightness control, so it must never flip Adaptive
     * Brightness off — but it must still re-apply, because the accessibility overlay needs
     * refreshing on every reconnect for the lockscreen to render correctly.
     */
    fun reapplyBrightness() {
        val ctx = stripContext ?: return
        // A screen-on retroactively explains any brightness change we were about to treat as a
        // user action: it was the OS restoring brightness for the wake.
        cancelOffStateRelease()
        handler.post { syncBrightnessOverrides(ctx, force = true) }
        // Keep attributing brightness writes to the OS for a moment after the screen comes back —
        // it can write more than once as the display settles — then hand control back to the user.
        clearWakeRestoreRunnable?.let { handler.removeCallbacks(it) }
        clearWakeRestoreRunnable = Runnable {
            pendingWakeRestore = false
            // One last assert once things have settled, in case a late OS write slipped through.
            stripContext?.let { syncBrightnessOverrides(it, force = true) }
        }
        handler.postDelayed(clearWakeRestoreRunnable!!, WAKE_RESTORE_GRACE_MS)
    }

    /**
     * Screen turned off. Arms the wake-restore guard — see [pendingWakeRestore]. Must be driven by
     * ACTION_SCREEN_OFF rather than ACTION_SCREEN_ON, because the OS's brightness restore beats
     * the SCREEN_ON broadcast to us.
     */
    fun onScreenOff() {
        clearWakeRestoreRunnable?.let { handler.removeCallbacks(it) }
        clearWakeRestoreRunnable = null
        cancelOffStateRelease()
        pendingWakeRestore = true
    }

    /**
     * Arm the pending "the user really did want light again" decision. Cancelled by a screen-on,
     * which retroactively identifies the brightness change as the OS's own wake restore.
     */
    private fun scheduleOffStateRelease(context: Context, observed: Int) {
        if (offStateReleaseRunnable != null) return  // already pending; don't restart the clock
        val r = Runnable {
            offStateReleaseRunnable = null
            if (!frontLightOff) return@Runnable
            // Act on the value that triggered this, not a fresh read: the assert below has since
            // republished 0, so re-reading the setting would always look like "already off" and
            // this release could never fire.
            frontLightOff = false
            setManualBrightness(context.contentResolver, observed)
            updateBrightnessUI(observed)
        }
        offStateReleaseRunnable = r
        handler.postDelayed(r, OFF_STATE_RELEASE_DELAY_MS)
    }

    private fun cancelOffStateRelease() {
        offStateReleaseRunnable?.let { handler.removeCallbacks(it) }
        offStateReleaseRunnable = null
    }

    /** Update the live brightness slider/icon UI if the panel is currently open. */
    private fun updateBrightnessUI(brightness: Int) {
        handler.post {
            val container = liveSliderContainer ?: return@post
            val thumbSize = liveSliderThumbSize
            val width = container.width
            if (width <= 0) return@post

            val usable = width - thumbSize
            val frac = brightness / 255f
            val thumbX = (frac * usable).toInt()
            liveSliderThumb?.let { t ->
                (t.layoutParams as? FrameLayout.LayoutParams)?.leftMargin = thumbX
                t.requestLayout()
            }
            liveSliderFill?.let { f ->
                (f.layoutParams as? FrameLayout.LayoutParams)?.width = thumbX + thumbSize / 2
                f.requestLayout()
            }
            liveValueText?.text = if (brightness == 0) "Off" else "${brightness * 100 / 255}%"
            liveSunIcon?.let { icon ->
                val ctx = icon.context
                icon.setImageDrawable(BitmapDrawable(ctx.resources,
                    drawBrightnessIcon(liveSunSize, false, slashed = brightness <= 0)))
            }
        }
    }

    /** Toggle front light between off (0) and last manual brightness. Callable from anywhere. */
    fun toggleFrontLight(context: Context) {
        val resolver = context.contentResolver
        // Mode-forcing lives in setManualBrightness() below — no need to write it twice here.
        val target = if (isFrontLightOff) {
            frontLightRestoreValue()
        } else {
            val current = try {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Exception) { 0 }
            if (current > 0) { lastManualBrightness = current; saveBrightness(context) }
            0
        }
        setManualBrightness(resolver, target)
        updateBrightnessUI(target)
        refreshPanelIfShowing()
    }

    /** Cycle e-ink refresh mode between the two configured modes, with a quick black flash. */
    fun cycleRefreshMode() {
        if (!MeinkController.isAvailable) return
        refreshPanelIfShowing()
        val ctx = stripContext ?: return
        showQuickFlash(ctx)
    }

    /** Quick black flash to engage e-ink mode change. */
    fun showQuickFlash(context: Context) {
        if (windowManager == null) {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        try {
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            val flash = View(context).apply { setBackgroundColor(PURE_BLACK) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            )
            windowManager?.addView(flash, params)
            handler.postDelayed({
                try { windowManager?.removeView(flash) } catch (_: Exception) {}
            }, 10)
        } catch (e: Exception) {
            Log.e(TAG, "Quick flash failed", e)
        }
    }

    private fun getStatusBarHeight(context: Context): Int {
        val rid = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (rid > 0) context.resources.getDimensionPixelSize(rid)
        else (24 * context.resources.displayMetrics.density).toInt()
    }

    private fun makeDivider(context: Context, dp: Float): View {
        return View(context).apply {
            setBackgroundColor(BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (2 * dp).toInt().coerceAtLeast(1)
            ).apply { topMargin = (4 * dp).toInt(); bottomMargin = (2 * dp).toInt() }
        }
    }

    private fun makeThinDivider(context: Context, dp: Float): View {
        return View(context).apply {
            setBackgroundColor(BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1)
            ).apply {
                topMargin = (2 * dp).toInt(); bottomMargin = (2 * dp).toInt()
                marginStart = (8 * dp).toInt(); marginEnd = (8 * dp).toInt()
            }
        }
    }

    private fun stripEmojis(text: String): String {
        return EMOJI_REGEX.replace(text, "").trim()
    }

    private fun formatTime(postTime: Long): String {
        val diff = System.currentTimeMillis() - postTime
        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86400_000 -> "${diff / 3600_000}h ago"
            else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(postTime))
        }
    }

    // ─── Drawn Icons (pure B/W, no emoji) ───────────────────────────

    private fun iconPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BLACK; style = Paint.Style.FILL
    }

    private fun drawPlayIcon(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = iconPaint()
        val path = Path().apply {
            moveTo(size * 0.2f, size * 0.1f)
            lineTo(size * 0.85f, size * 0.5f)
            lineTo(size * 0.2f, size * 0.9f)
            close()
        }
        c.drawPath(path, p)
        return bmp
    }

    private fun drawPauseIcon(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = iconPaint()
        val barW = size * 0.25f
        c.drawRect(size * 0.15f, size * 0.1f, size * 0.15f + barW, size * 0.9f, p)
        c.drawRect(size * 0.6f, size * 0.1f, size * 0.6f + barW, size * 0.9f, p)
        return bmp
    }

    private fun drawPrevIcon(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = iconPaint()
        // Left bar
        c.drawRect(size * 0.1f, size * 0.15f, size * 0.22f, size * 0.85f, p)
        // Triangle pointing left
        val path = Path().apply {
            moveTo(size * 0.85f, size * 0.15f)
            lineTo(size * 0.3f, size * 0.5f)
            lineTo(size * 0.85f, size * 0.85f)
            close()
        }
        c.drawPath(path, p)
        return bmp
    }

    private fun drawNextIcon(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = iconPaint()
        // Right bar
        c.drawRect(size * 0.78f, size * 0.15f, size * 0.9f, size * 0.85f, p)
        // Triangle pointing right
        val path = Path().apply {
            moveTo(size * 0.15f, size * 0.15f)
            lineTo(size * 0.7f, size * 0.5f)
            lineTo(size * 0.15f, size * 0.85f)
            close()
        }
        c.drawPath(path, p)
        return bmp
    }

    private fun drawMusicNoteIcon(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = iconPaint()
        // Note head (oval at bottom-left)
        val noteR = size * 0.18f
        c.drawCircle(size * 0.3f, size * 0.78f, noteR, p)
        // Stem
        val stemP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BLACK; style = Paint.Style.STROKE; strokeWidth = size * 0.08f
        }
        c.drawLine(size * 0.3f + noteR - size * 0.04f, size * 0.78f, size * 0.3f + noteR - size * 0.04f, size * 0.15f, stemP)
        // Flag
        val flagP = iconPaint()
        val flagPath = Path().apply {
            moveTo(size * 0.3f + noteR - size * 0.04f, size * 0.15f)
            quadTo(size * 0.75f, size * 0.2f, size * 0.65f, size * 0.45f)
            quadTo(size * 0.7f, size * 0.25f, size * 0.3f + noteR - size * 0.04f, size * 0.35f)
            close()
        }
        c.drawPath(flagPath, flagP)
        return bmp
    }
}
