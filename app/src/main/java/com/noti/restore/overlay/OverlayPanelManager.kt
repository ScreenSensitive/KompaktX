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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.database.ContentObserver
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
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
import com.noti.restore.ui.TriggerMode

private const val TAG = "OverlayPanelManager"

// Pure B/W for e-ink
private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val BORDER_WIDTH_DP = 2f

// Emoji regex for stripping from app labels
private val EMOJI_REGEX = Regex("[\\p{So}\\p{Sk}\\p{Cs}\\x{200D}\\x{FE0F}\\x{FE0E}\\x{20E3}\\x{E0020}-\\x{E007F}]+")

object OverlayPanelManager {

    private var windowManager: WindowManager? = null
    private var touchStripView: View? = null
    private var brightnessOverlay: View? = null  // Persistent full-screen overlay just for screenBrightness
    private var brightnessObserver: ContentObserver? = null  // Watches system brightness changes
    private var panelView: View? = null         // The root FrameLayout attached to WindowManager
    private var panelContentView: ViewGroup? = null  // The inner LinearLayout we swap content in
    private var headsUpView: View? = null
    private var headsUpParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var replyBoxOpen = false

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
    var hiddenNotiApps: Set<String> = emptySet()
    var hiddenRecentApps: Set<String> = emptySet()

    // Track which WM was used for heads-up (accessibility overlay vs normal)
    private var headsUpUsingAccessibility = false

    // Fallback heights in dp (only used if measurement fails)
    private const val PAGE_NAV_HEIGHT_DP = 44f

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

        // Watch for system brightness changes and override with our target
        brightnessObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val target = currentBrightnessTarget
                if (target < 0) return
                try {
                    val sys = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                    if (sys != target) {
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
                        val screenVal = if (target <= 0) 0.001f else target / 255f
                        applyScreenBrightnessToView(brightnessOverlay, screenVal)
                    }
                } catch (_: Exception) {}
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false, brightnessObserver!!
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
                    width = (20 * dp).toInt(), height = (100 * dp).toInt(),
                    gravityH = Gravity.START, gravityV = Gravity.TOP, yPos = 0
                ) { event ->
                    handleHorizontalSwipe(event, swipeThresholdPx, swipeRight = true) { showPanel(context) }
                }
            }
            TriggerMode.SWIPE_RIGHT_EDGE -> {
                setupEdgeStrip(context, windowType, dp,
                    width = (20 * dp).toInt(), height = (100 * dp).toInt(),
                    gravityH = Gravity.END, gravityV = Gravity.TOP, yPos = 0
                ) { event ->
                    handleHorizontalSwipe(event, swipeThresholdPx, swipeRight = false) { showPanel(context) }
                }
            }
            TriggerMode.PULL_UP_BOTTOM_RIGHT -> {
                setupEdgeStrip(context, windowType, dp,
                    width = (40 * dp).toInt(), height = (100 * dp).toInt(),
                    gravityH = Gravity.END, gravityV = Gravity.BOTTOM, yPos = 0
                ) { event ->
                    handleVerticalSwipe(event, swipeThresholdPx, swipeUp = true) { showPanel(context) }
                }
            }
            TriggerMode.PULL_UP_BOTTOM_LEFT -> {
                setupEdgeStrip(context, windowType, dp,
                    width = (40 * dp).toInt(), height = (100 * dp).toInt(),
                    gravityH = Gravity.START, gravityV = Gravity.BOTTOM, yPos = 0
                ) { event ->
                    handleVerticalSwipe(event, swipeThresholdPx, swipeUp = true) { showPanel(context) }
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

                val flashView = View(context).apply { setBackgroundColor(BLACK) }
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
                    try { flashView.setBackgroundColor(WHITE) } catch (_: Exception) {}
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
    private var panelTab = 0  // 0 = notifications, 1 = settings

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

            // Scrim - tap outside to close
            val scrim = View(context).apply {
                setBackgroundColor(WHITE)
                setOnClickListener { dismissPanel() }
            }
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP }

            try {
                windowManager?.addView(rootFrame, wlp)
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
        val timeStr = android.text.format.DateFormat.format("h:mm a", now).toString()
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
        dateCol.addView(TextView(context).apply {
            text = "$dayOfWeek  |  $timeStr"; setTextColor(BLACK); textSize = baseTinySize
        })
        topBar.addView(dateCol)
        // Battery percentage + close button column on the right
        val rightCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((8 * dp).toInt(), 0, 0, 0)
        }
        if (batteryLevel != null) {
            rightCol.addView(TextView(context).apply {
                text = "$batteryLevel%"; setTextColor(BLACK); textSize = baseSmallSize
                typeface = Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
            })
        }
        rightCol.addView(TextView(context).apply {
            text = "\u2715"; setTextColor(BLACK); textSize = baseTitleSize + 4f
            gravity = android.view.Gravity.CENTER
            setOnClickListener { dismissPanel() }
        })
        topBar.addView(rightCol)
        panelCard.addView(topBar)

        // ── Tab switcher: small pill toggle ──
        val tabBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
        }
        val tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val bg = GradientDrawable().apply {
                cornerRadius = 100 * dp; setStroke(borderW, BLACK); setColor(WHITE)
            }
            background = bg
        }
        val tabLabels = listOf("Notifications", "Settings")
        for (i in tabLabels.indices) {
            val selected = i == panelTab
            val tabBg = GradientDrawable().apply {
                cornerRadius = 100 * dp
                if (selected) setColor(BLACK) else setColor(0x00000000) // transparent
            }
            tabContainer.addView(TextView(context).apply {
                text = tabLabels[i]
                setTextColor(if (selected) WHITE else BLACK)
                textSize = baseSmallSize; typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER; background = tabBg
                setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener {
                    if (panelTab != i) { panelTab = i; buildAndShowPanel(context) }
                }
            })
        }
        tabBar.addView(tabContainer)
        panelCard.addView(tabBar)
        panelCard.addView(makeThinDivider(context, dp))

        // ── Tab content ──
        if (panelTab == 0) {
            // ─── Notifications tab ───
            // Media widget at top if playing
            val mediaInfo = KompaktXListener.externalMediaInfo.value
            if (mediaInfo != null && mediaInfo.title != null) {
                panelCard.addView(buildMediaWidget(context, dp, mediaInfo))
                panelCard.addView(makeThinDivider(context, dp))
            }

            // Clear row
            if (allFlatNotifications.isNotEmpty()) {
                val clearRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                    setPadding((14 * dp).toInt(), (2 * dp).toInt(), (14 * dp).toInt(), (0 * dp).toInt())
                }
                clearRow.addView(TextView(context).apply {
                    text = "Clear All"; setTextColor(BLACK); textSize = baseTinySize
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                    background = GradientDrawable().apply {
                        setColor(WHITE); setStroke(borderW, BLACK); cornerRadius = 100 * dp
                    }
                    setOnClickListener {
                        refreshRunnable?.let { handler.removeCallbacks(it) }
                        KompaktXListener.clearAll(); dismissPanel()
                    }
                })
                panelCard.addView(clearRow)
            }

            // Scrollable notification list
            val notiScroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ); isVerticalScrollBarEnabled = false
            }
            val notiContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * dp).toInt(), (4 * dp).toInt(), (14 * dp).toInt(), (4 * dp).toInt())
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
            panelCard.addView(notiScroll)

        } else {
            // ─── Settings tab ───
            val settingsScroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ); isVerticalScrollBarEnabled = false
            }
            val settingsContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            }

            // Toggle pills (WiFi, BT, Light)
            settingsContent.addView(buildSettingsGrid(context, dp, borderW))

            // Brightness slider right below toggles
            settingsContent.addView(buildBrightnessSection(context, dp))

            // Refresh mode circles
            if (MeinkController.isAvailable) {
                settingsContent.addView(buildRefreshModeCircles(context, dp, borderW))
            }


            settingsScroll.addView(settingsContent)
            panelCard.addView(settingsScroll)
        }

        // ── Bottom bar: recents ──
        if (recentAppsEnabled) {
            panelCard.addView(makeThinDivider(context, dp).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = (4 * dp).toInt()
            })
        }

        if (recentAppsEnabled) {
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

    private fun buildAppHeader(context: Context, dp: Float, pkg: String, appName: String, count: Int): View {
        val appHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
        }
        val iconSize = (20 * dp).toInt()
        appHeader.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = (6 * dp).toInt() }
            try { setImageDrawable(context.packageManager.getApplicationIcon(pkg)) } catch (_: Exception) {}
        })
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
    }

    // ─── Notification Entry (no card outline, just content with dividers) ──

    private fun buildNotificationEntry(context: Context, dp: Float, sbn: StatusBarNotification): View {
        val extras = sbn.notification.extras
        val nTitle = extras.getString("android.title") ?: ""
        val nText = extras.getCharSequence("android.text")?.toString() ?: ""
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

        // App icon on the left
        val iconSize = (24 * dp).toInt()
        entry.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = (8 * dp).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
            try { setImageDrawable(context.packageManager.getApplicationIcon(sbn.packageName)) } catch (_: Exception) {}
        })

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
            setPadding((8 * dp).toInt(), 0, 0, 0)
            setOnClickListener {
                KompaktXListener.dismiss(sbn.key)
                (entry.parent as? ViewGroup)?.removeView(entry)
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
    private var currentBrightnessTarget = -1  // tracks what brightness we last set (-1 = not managed)

    // Live references to brightness UI elements (set when panel is open, cleared on close)
    private var liveSunIcon: ImageView? = null
    private var liveSunSize = 0
    private var liveSliderContainer: FrameLayout? = null
    private var liveSliderThumb: View? = null
    private var liveSliderFill: View? = null
    private var liveSliderThumbSize = 0
    private var liveValueText: TextView? = null

    /** Load saved brightness from SharedPreferences */
    fun loadSavedBrightness(context: Context) {
        val sp = context.getSharedPreferences("kompaktx_brightness", Context.MODE_PRIVATE)
        lastManualBrightness = sp.getInt("last_manual_brightness", 128)
        currentBrightnessTarget = sp.getInt("current_brightness_target", -1)
        // If we have a saved target, apply it immediately
        if (currentBrightnessTarget >= 0) {
            applyBrightness(context.contentResolver, currentBrightnessTarget)
        }
    }

    private fun saveBrightness(context: Context?) {
        context ?: return
        try {
            val sp = context.getSharedPreferences("kompaktx_brightness", Context.MODE_PRIVATE)
            sp.edit()
                .putInt("last_manual_brightness", lastManualBrightness)
                .putInt("current_brightness_target", currentBrightnessTarget)
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
        val currentBrightness = try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { 128 }

        // Ensure manual brightness mode
        try {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        } catch (_: Exception) {}

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
                    try {
                        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
                    } catch (_: Exception) {}
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

    private fun drawWifiIcon(size: Int, on: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val color = if (on) WHITE else BLACK
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE
            strokeWidth = size * 0.12f; strokeCap = Paint.Cap.ROUND
        }
        val cx = size / 2f; val bottom = size * 0.88f
        // Three bold arcs, well-spaced for e-ink clarity
        for (i in 1..3) {
            val r = size * 0.18f * i
            c.drawArc(cx - r, bottom - r * 2, cx + r, bottom, -140f, 100f, false, p)
        }
        // Larger filled dot at bottom
        p.style = Paint.Style.FILL
        c.drawCircle(cx, bottom, size * 0.09f, p)
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

        // ── Row 1: WiFi + Bluetooth + Flashlight as 3 equal pill buttons ──
        val pillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 0, 0, (10 * dp).toInt())
        }

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

        for ((idx, pill) in pills.withIndex()) {
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
                    if (idx > 0) marginStart = (4 * dp).toInt()
                    if (idx < pills.size - 1) marginEnd = (4 * dp).toInt()
                }
                setOnClickListener {
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
            pillRow.addView(pillView)
        }
        grid.addView(pillRow)
        return grid
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
        val currentBrightness = try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { 128 }
        try {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        } catch (_: Exception) {}

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
                    applyBrightness(resolver, b)
                    updateSlider(b, v.width)
                    sunIcon.setImageDrawable(BitmapDrawable(context.resources,
                        drawBrightnessIcon(sunSize, false, slashed = b <= 0)))
                    true
                }
                else -> true
            }
        }
        sunIcon.setOnClickListener {
            val current = try {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Exception) { 0 }
            val target = if (current > 0) {
                lastManualBrightness = current  // save before turning off
                saveBrightness(context)
                0
            } else {
                lastManualBrightness.coerceAtLeast(10)  // restore, min 10 so it's visible
            }
            applyBrightness(resolver, target)
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
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${appInfo.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent); dismissPanel(); true
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
            item.addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                try { setImageDrawable(context.packageManager.getApplicationIcon(appInfo.packageName)) } catch (_: Exception) {}
            })
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
            removeHeadsUp(); replyBoxOpen = false
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
                // Prefer TYPE_ACCESSIBILITY_OVERLAY (draws above status bar); fall back to normal overlay
                headsUpUsingAccessibility = com.noti.restore.service.RecentsButtonService.addHeadsUp(view, layoutParams)
                if (!headsUpUsingAccessibility) windowManager?.addView(view, layoutParams)
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
        val iconSize = (28 * dp).toInt()
        headerRow.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = padSmall }
            try { setImageDrawable(context.packageManager.getApplicationIcon(data.packageName)) } catch (_: Exception) {}
        })
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
            card.addView(TextView(context).apply {
                text = data.text; setTextColor(BLACK); textSize = baseTextSize + 2f; maxLines = 3
                ellipsize = TextUtils.TruncateAt.END; setPadding(0, (4 * dp).toInt(), 0, 0)
            })
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
        }
        replyContainer.addView(replyInput)
        val sendBtnBg = GradientDrawable().apply { setColor(BLACK); cornerRadius = 8 * dp }
        replyContainer.addView(TextView(context).apply {
            text = "\u27A4"; setTextColor(WHITE); textSize = baseTitleSize + 2f; gravity = Gravity.CENTER; background = sendBtnBg
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply { marginStart = padSmall }
            setOnClickListener {
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

    private fun removeHeadsUp() {
        cancelDismissTimer(); replyBoxOpen = false
        headsUpView?.let {
            if (headsUpUsingAccessibility) com.noti.restore.service.RecentsButtonService.removeHeadsUp(it)
            else try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        headsUpView = null; headsUpParams = null; headsUpUsingAccessibility = false
    }

    private fun startDismissTimer() {
        cancelDismissTimer(); dismissRunnable = Runnable { removeHeadsUp() }
        handler.postDelayed(dismissRunnable!!, 5000L)
    }
    private fun resetDismissTimer() { if (!replyBoxOpen) startDismissTimer() }
    private fun cancelDismissTimer() { dismissRunnable?.let { handler.removeCallbacks(it) }; dismissRunnable = null }

    // ─── Helpers ────────────────────────────────────────────────────

    private fun applyBrightness(resolver: android.content.ContentResolver, brightness: Int) {
        currentBrightnessTarget = brightness
        try {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
        } catch (_: Exception) {}
        val screenVal = if (brightness <= 0) 0.001f else brightness / 255f
        applyScreenBrightnessToView(panelView, screenVal)
        applyScreenBrightnessToView(brightnessOverlay, screenVal)
        // Also apply via accessibility overlay — persists above lockscreen
        com.noti.restore.service.RecentsButtonService.applyAccessibilityBrightness(screenVal)
        saveBrightness(stripContext)
    }

    private fun applyScreenBrightnessToView(view: View?, brightness: Float) {
        view ?: return
        try {
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
            lp.screenBrightness = brightness
            windowManager?.updateViewLayout(view, lp)
        } catch (_: Exception) {}
    }

    /** Re-apply our brightness target after screen wake. Forces both Settings.System and overlay. */
    fun reapplyBrightness() {
        val ctx = stripContext ?: return
        val target = currentBrightnessTarget
        if (target < 0) return
        handler.post { applyBrightness(ctx.contentResolver, target) }
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
        try {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        } catch (_: Exception) {}
        val current = try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { 0 }
        val target = if (current > 0) {
            lastManualBrightness = current
            saveBrightness(context)
            0
        } else {
            lastManualBrightness.coerceAtLeast(10)
        }
        applyBrightness(resolver, target)
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
            val flash = View(context).apply { setBackgroundColor(BLACK) }
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
