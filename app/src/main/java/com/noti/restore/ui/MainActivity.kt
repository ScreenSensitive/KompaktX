package com.noti.restore.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noti.restore.eink.MeinkController
import com.noti.restore.overlay.OverlayPanelManager
import com.noti.restore.overlay.OverlayService
import com.noti.restore.service.KompaktXListener
import com.noti.restore.service.RecentsButtonService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MeinkController.init()
        // Seed the theme before the first frame — see the theme cache below.
        seedThemeFromCache(this)
        setContent { KompaktXApp() }
    }
}

// In-app theme — backed by a Compose State so any composable that reads BG/FG/CARD_BG/BORDER
// is automatically tracked and recomposed when the active theme flips.
private val darkModeState = mutableStateOf(false)

// The real theme preference lives in DataStore, which is read asynchronously — so the first
// composed frame lands before it arrives and the window would paint in whatever this state
// happens to hold. Mirroring the last resolved value into SharedPreferences, which reads
// synchronously, lets onCreate seed it correctly before anything is drawn. DataStore stays the
// source of truth; this is only a fast path to avoid opening in the wrong theme.
private const val THEME_CACHE_PREFS = "kompaktx_theme_cache"
private const val THEME_CACHE_KEY = "last_resolved_dark"

private fun seedThemeFromCache(context: Context) {
    darkModeState.value = try {
        context.getSharedPreferences(THEME_CACHE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(THEME_CACHE_KEY, false)
    } catch (_: Exception) { false }
}

private fun cacheResolvedTheme(context: Context, isDark: Boolean) {
    try {
        context.getSharedPreferences(THEME_CACHE_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(THEME_CACHE_KEY, isDark).apply()
    } catch (_: Exception) {}
}

private val BG: Color get() = if (darkModeState.value) Color.Black else Color.White
private val FG: Color get() = if (darkModeState.value) Color.White else Color.Black
private val CARD_BG: Color get() = BG
private val BORDER: Color get() = FG

@Composable
fun KompaktXApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesManager.getInstance(context) }

    // Permission state can only change while the user is away in system settings, so this polls
    // to notice their return. Scoped to RESUMED so it stops entirely once the app is backgrounded
    // rather than recomposing the whole screen every 2s for the life of the activity.
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { delay(2000); tick++ }
        }
    }

    val hasOverlayPermission = remember(tick) { Settings.canDrawOverlays(context) }
    val hasNotificationAccess = remember(tick) { KompaktXListener.isNotificationAccessGranted(context) }
    val hasUsageAccess = remember(tick) { hasUsageStatsPermission(context) }
    val hasAccessibility = remember(tick) { RecentsButtonService.isRunning }
    val hasBatteryOptExemption = remember(tick) { isIgnoringBatteryOptimizations(context) }
    val hasWriteSettings = remember(tick) { Settings.System.canWrite(context) }

    val headsUpEnabled by prefs.headsUpEnabled.collectAsState(initial = true)
    val recentAppsEnabled by prefs.recentAppsEnabled.collectAsState(initial = true)
    val triggerMode by prefs.triggerMode.collectAsState(initial = TriggerMode.SWIPE_LEFT_EDGE)
    val fontSizeOffset by prefs.fontSizeOffset.collectAsState(initial = 0)
    val einkControlsEnabled by prefs.einkControlsEnabled.collectAsState(initial = false)
    val hiddenNotiApps by prefs.hiddenNotiApps.collectAsState(initial = emptySet())
    val hiddenRecentApps by prefs.hiddenRecentApps.collectAsState(initial = emptySet())
    val recentsLayoutMode by prefs.recentsLayoutMode.collectAsState(initial = RecentsLayoutMode.INLINE)
    val hideAppIcons by prefs.hideAppIcons.collectAsState(initial = false)
    // Collected as nullable so that "DataStore hasn't answered yet" is distinguishable from a
    // real stored value. Every flow here comes from the same DataStore, so one arriving means
    // they all have — this doubles as the readiness gate for the whole screen below.
    val storedThemeMode by remember { prefs.themeMode.map { it as Int? } }
        .collectAsState(initial = null)
    val prefsLoaded = storedThemeMode != null
    val themeMode = storedThemeMode ?: ThemeMode.SYSTEM
    val tabPosition by prefs.tabPosition.collectAsState(initial = TabPosition.TOP)

    // Resolve effective dark state from the theme pref + system uiMode.
    val systemDark = (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }
    // Only take over the theme once the stored preference is actually known. Until then keep the
    // value onCreate seeded from cache, rather than briefly asserting the SYSTEM fallback and
    // flipping the whole window a frame later.
    if (prefsLoaded && darkModeState.value != isDark) {
        darkModeState.value = isDark
        cacheResolvedTheme(context, isDark)
    }
    // Match system bar icon tint to the active theme so they stay legible.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !isDark
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightNavigationBars = !isDark
        }
    }
    LaunchedEffect(isDark) { OverlayPanelManager.darkMode = isDark }
    LaunchedEffect(tabPosition) { OverlayPanelManager.tabPosition = tabPosition }

    // Shortcut assignments
    val shortcutFrontLight by prefs.shortcutFrontLight.collectAsState(initial = ShortcutTrigger.NONE)
    val shortcutEinkRefresh by prefs.shortcutEinkRefresh.collectAsState(initial = ShortcutTrigger.NONE)
    val shortcutRefreshCycle by prefs.shortcutRefreshCycle.collectAsState(initial = ShortcutTrigger.NONE)
    val volumeLongPressMedia by prefs.volumeLongPressMedia.collectAsState(initial = false)
    val autoStart by prefs.autoStart.collectAsState(initial = true)
    val hapticsEnabled by prefs.hapticsEnabled.collectAsState(initial = true)
    val headsUpDuration by prefs.headsUpDuration.collectAsState(initial = 5000)
    val headsUpQueue by prefs.headsUpQueue.collectAsState(initial = false)
    var showHeadsUpPage by remember { mutableStateOf(false) }
    BackHandler(enabled = showHeadsUpPage) { showHeadsUpPage = false }

    if (showHeadsUpPage) {
        HeadsUpScreen(
            enabled = headsUpEnabled,
            durationMs = headsUpDuration,
            queueMode = headsUpQueue,
            onToggle = { scope.launch { prefs.setHeadsUpEnabled(it) } },
            onDuration = { scope.launch { prefs.setHeadsUpDuration(it) } },
            onQueueMode = { scope.launch { prefs.setHeadsUpQueue(it) } },
            onBack = { showHeadsUpPage = false }
        )
        return
    }
    val showLocationToggle by prefs.showLocationToggle.collectAsState(initial = false)
    // Re-checked on the status tick so granting it over adb takes effect without a restart.
    val hasSecureSettings = remember(tick) {
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var showLocationPage by remember { mutableStateOf(false) }
    BackHandler(enabled = showLocationPage) { showLocationPage = false }

    if (showLocationPage) {
        LocationToggleScreen(
            granted = hasSecureSettings,
            enabled = showLocationToggle,
            onToggle = { scope.launch { prefs.setShowLocationToggle(it) } },
            onBack = { showLocationPage = false }
        )
        return
    }

    LaunchedEffect(headsUpEnabled) { OverlayPanelManager.headsUpEnabled = headsUpEnabled }
    LaunchedEffect(recentAppsEnabled) { OverlayPanelManager.recentAppsEnabled = recentAppsEnabled }
    LaunchedEffect(triggerMode) {
        OverlayPanelManager.triggerMode = triggerMode
        OverlayPanelManager.recreateTouchStrip()
    }
    LaunchedEffect(fontSizeOffset) { OverlayPanelManager.fontSizeOffset = fontSizeOffset }
    LaunchedEffect(einkControlsEnabled) { OverlayPanelManager.einkControlsEnabled = einkControlsEnabled }
    LaunchedEffect(shortcutFrontLight, shortcutEinkRefresh, shortcutRefreshCycle) {
        // shortcut_refresh_cycle used to be passed as a hardcoded NONE here, so the stored
        // preference could never reach the service and the action was unreachable.
        RecentsButtonService.updateShortcuts(shortcutFrontLight, shortcutEinkRefresh, shortcutRefreshCycle)
    }
    LaunchedEffect(volumeLongPressMedia) { RecentsButtonService.volumeLongPressMediaEnabled = volumeLongPressMedia }
    LaunchedEffect(hiddenNotiApps) {
        OverlayPanelManager.hiddenNotiApps = hiddenNotiApps
        KompaktXListener.setHiddenNotiApps(hiddenNotiApps)
    }
    LaunchedEffect(hiddenRecentApps) { OverlayPanelManager.hiddenRecentApps = hiddenRecentApps }
    LaunchedEffect(recentsLayoutMode) { OverlayPanelManager.recentsLayoutMode = recentsLayoutMode }
    LaunchedEffect(hideAppIcons) { OverlayPanelManager.hideAppIcons = hideAppIcons }

    val allPermissionsGranted = hasOverlayPermission && hasNotificationAccess
    val allRequiredGranted = allPermissionsGranted && hasUsageAccess && hasAccessibility && hasBatteryOptExemption && hasWriteSettings

    LaunchedEffect(allPermissionsGranted) {
        if (allPermissionsGranted) {
            try { context.startForegroundService(Intent(context, OverlayService::class.java)) } catch (_: Exception) {}
        }
    }

    // Track which settings section is expanded
    var showHiddenNotiApps by remember { mutableStateOf(false) }
    // Expanded while anything is outstanding — then it is the only thing that matters here.
    var showPermissions by remember(allRequiredGranted) { mutableStateOf(!allRequiredGranted) }
    var showHiddenRecentApps by remember { mutableStateOf(false) }

    // Hold the (correctly themed) empty screen until the stored preferences arrive. Every toggle
    // below is collected with a hardcoded default as its pre-DataStore value, so rendering now
    // would briefly show a screen full of defaults and then snap to the real configuration.
    if (!prefsLoaded) {
        Box(Modifier.fillMaxSize().background(BG))
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("KompaktX", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = FG)
        Spacer(Modifier.height(20.dp))

        SettingsCard {
            ToggleRow(
                "Start on Boot",
                if (autoStart) "Comes back automatically after a restart"
                else "Stays off until you open this app",
                Icons.Default.RestartAlt, autoStart
            ) { scope.launch { prefs.setAutoStart(it) } }
        }

        Spacer(Modifier.height(20.dp))

        // Permissions — always present, so you can see what you have granted and revoke it, not
        // only what is still outstanding. Collapsed to a count once everything is in place;
        // expanded by default while anything is missing, since then it is the only thing that
        // matters on this screen.
        val grantedCount = listOf(
            hasNotificationAccess, hasOverlayPermission, hasUsageAccess,
            hasAccessibility, hasBatteryOptExemption, hasWriteSettings
        ).count { it }
        SectionHeader("Permissions")
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPermissions = !showPermissions }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (grantedCount == 6) Icons.Default.CheckCircle else Icons.Default.Warning,
                    null, tint = FG, modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("$grantedCount of 6 granted", fontWeight = FontWeight.Bold,
                        fontSize = 14.sp, color = FG)
                    Text(
                        if (grantedCount == 6) "Everything KompaktX needs is enabled"
                        else "Tap to see what is missing",
                        fontSize = 12.sp, color = FG
                    )
                }
                Icon(
                    if (showPermissions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = FG, modifier = Modifier.size(20.dp)
                )
            }
            if (showPermissions) {
                Divider(color = FG, thickness = 1.dp)
                PermissionRow("Notification Access", "Required to read notifications",
                    Icons.Default.Notifications, hasNotificationAccess
                ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                Divider(color = FG, thickness = 1.dp)
                PermissionRow("Display Over Other Apps", "Required for notification panel",
                    Icons.Default.Layers, hasOverlayPermission
                ) { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }) }
                Divider(color = FG, thickness = 1.dp)
                PermissionRow("Usage Access", "Required for recent apps",
                    Icons.Default.History, hasUsageAccess
                ) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                Divider(color = FG, thickness = 1.dp)
                PermissionRow("Accessibility Service", "Required for Recents button trigger",
                    Icons.Default.Accessibility, hasAccessibility
                ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                Divider(color = FG, thickness = 1.dp)
                PermissionRow("Disable Battery Optimization", "Prevents the system from killing the overlay",
                    Icons.Default.BatteryFull, hasBatteryOptExemption
                ) { requestIgnoreBatteryOptimizations(context) }
                Divider(color = FG, thickness = 1.dp)
                PermissionRow("Modify System Settings", "Required for brightness, adaptive brightness, and custom tones",
                    Icons.Default.Tune, hasWriteSettings
                ) { context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }) }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Trigger Settings
        SectionHeader("Notification Tray Shortcut")
        SettingsCard {
            TriggerOption("Swipe right from left edge", TriggerMode.SWIPE_LEFT_EDGE, triggerMode) {
                scope.launch { prefs.setTriggerMode(TriggerMode.SWIPE_LEFT_EDGE) }
            }
            Divider(color = FG, thickness = 1.dp)
            TriggerOption("Swipe left from right edge", TriggerMode.SWIPE_RIGHT_EDGE, triggerMode) {
                scope.launch { prefs.setTriggerMode(TriggerMode.SWIPE_RIGHT_EDGE) }
            }
            Divider(color = FG, thickness = 1.dp)
            TriggerOption("Pull up from bottom-right edge", TriggerMode.PULL_UP_BOTTOM_RIGHT, triggerMode) {
                scope.launch { prefs.setTriggerMode(TriggerMode.PULL_UP_BOTTOM_RIGHT) }
            }
            Divider(color = FG, thickness = 1.dp)
            TriggerOption("Pull up from bottom-left edge", TriggerMode.PULL_UP_BOTTOM_LEFT, triggerMode) {
                scope.launch { prefs.setTriggerMode(TriggerMode.PULL_UP_BOTTOM_LEFT) }
            }
            Divider(color = FG, thickness = 1.dp)
            TriggerOption("Recents button (needs Accessibility)", TriggerMode.RECENTS_BUTTON, triggerMode) {
                scope.launch { prefs.setTriggerMode(TriggerMode.RECENTS_BUTTON) }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Feature Toggles
        SectionHeader("Features")
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { showHeadsUpPage = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.NotificationsActive, null, tint = FG, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Notification Popups", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FG)
                    Text(
                        if (headsUpEnabled) "On · ${headsUpDuration / 1000}s on screen"
                        else "Off",
                        fontSize = 12.sp, color = FG
                    )
                }
                Icon(Icons.Default.ChevronRight, null, tint = FG, modifier = Modifier.size(20.dp))
            }
            Divider(color = FG, thickness = 1.dp)
            ToggleRow("Recent Apps", "Show recently used apps in panel",
                Icons.Default.Apps, recentAppsEnabled
            ) { scope.launch { prefs.setRecentAppsEnabled(it) } }
            if (recentAppsEnabled) {
                Divider(color = FG, thickness = 1.dp)
                RecentsLayoutPicker(recentsLayoutMode) {
                    scope.launch { prefs.setRecentsLayoutMode(it) }
                }
            }
            Divider(color = FG, thickness = 1.dp)
            TabPositionPicker(tabPosition) { scope.launch { prefs.setTabPosition(it) } }
            Divider(color = FG, thickness = 1.dp)
            ToggleRow("Hide App Icons", "Hide icons in recents and notifications",
                Icons.Default.HideImage, hideAppIcons
            ) { scope.launch { prefs.setHideAppIcons(it) } }
            Divider(color = FG, thickness = 1.dp)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { showLocationPage = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LocationOn, null, tint = FG, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Location Toggle", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FG)
                    Text(
                        if (!hasSecureSettings) "Needs setup — tap to see how"
                        else if (showLocationToggle) "Shown in the panel"
                        else "Ready — not shown",
                        fontSize = 12.sp, color = FG
                    )
                }
                Icon(Icons.Default.ChevronRight, null, tint = FG, modifier = Modifier.size(20.dp))
            }
            Divider(color = FG, thickness = 1.dp)
            ToggleRow("Haptic Feedback", "Short buzz when panel buttons and tabs are pressed",
                Icons.Default.Vibration, hapticsEnabled
            ) { scope.launch { prefs.setHapticsEnabled(it) } }
            Divider(color = FG, thickness = 1.dp)
            ToggleRow("E-Ink Controls", if (MeinkController.isAvailable) "Show display mode buttons in panel" else "Not available on this device",
                Icons.Default.Contrast, einkControlsEnabled
            ) { scope.launch { prefs.setEinkControlsEnabled(it) } }
        }

        Spacer(Modifier.height(20.dp))

        // Hardware Shortcuts
        SectionHeader("Shortcuts")
        Text(
            "Assign hardware button shortcuts. Each trigger can only be used once — giving it to one action clears it from the other. Requires Accessibility Service.",
            fontSize = 12.sp, color = FG, modifier = Modifier.padding(bottom = 8.dp)
        )
        SettingsCard {
            ShortcutRow(
                action = "front_light",
                label = "Front Light Toggle",
                description = "Toggle front light on/off",
                icon = Icons.Default.LightMode,
                currentTrigger = shortcutFrontLight,
                onTriggerChange = { scope.launch { prefs.setShortcutTrigger("front_light", it) } }
            )
            Divider(color = FG, thickness = 1.dp)
            ShortcutRow(
                action = "eink_refresh",
                label = "E-Ink Refresh",
                description = "Flash black/white to clear ghosting",
                icon = Icons.Default.Refresh,
                currentTrigger = shortcutEinkRefresh,
                onTriggerChange = { scope.launch { prefs.setShortcutTrigger("eink_refresh", it) } }
            )
            Divider(color = FG, thickness = 1.dp)
            ToggleRow("Volume Long Press → Media Skip",
                "Long press Vol Up/Down to skip/prev track (only when media is playing)",
                Icons.Default.SkipNext, volumeLongPressMedia
            ) { scope.launch { prefs.setVolumeLongPressMedia(it) } }
        }

        Spacer(Modifier.height(20.dp))

        // Font Size
        SectionHeader("Font Size")
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                data class FontPreset(val label: String, val offset: Int)
                val presets = listOf(
                    FontPreset("Small", 0),
                    FontPreset("Medium", 2),
                    FontPreset("Large", 4)
                )
                presets.forEach { preset ->
                    val isSelected = fontSizeOffset == preset.offset
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .border(2.dp, FG, RoundedCornerShape(8.dp))
                            .then(
                                if (isSelected) Modifier.background(FG, RoundedCornerShape(8.dp))
                                else Modifier
                            )
                            .clickable { scope.launch { prefs.setFontSizeOffset(preset.offset) } }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            preset.label,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) BG else FG
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Theme
        SectionHeader("Theme")
        SettingsCard {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                data class ThemeOpt(val label: String, val mode: Int)
                val opts = listOf(
                    ThemeOpt("System", ThemeMode.SYSTEM),
                    ThemeOpt("Light", ThemeMode.LIGHT),
                    ThemeOpt("Dark", ThemeMode.DARK)
                )
                opts.forEach { opt ->
                    val isSelected = themeMode == opt.mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .border(2.dp, FG, RoundedCornerShape(8.dp))
                            .then(if (isSelected) Modifier.background(FG, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable { scope.launch { prefs.setThemeMode(opt.mode) } }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(opt.label, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = if (isSelected) BG else FG)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Hidden Apps
        SectionHeader("App Filters")
        SettingsCard {
            // Hidden from Notifications
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showHiddenNotiApps = !showHiddenNotiApps }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.NotificationsOff, null, tint = FG, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hidden from Notifications", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FG)
                    Text("${hiddenNotiApps.size} apps hidden", fontSize = 12.sp, color = FG)
                }
                Icon(
                    if (showHiddenNotiApps) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = FG, modifier = Modifier.size(20.dp)
                )
            }
            if (showHiddenNotiApps) {
                Divider(color = FG, thickness = 1.dp)
                AppFilterList(
                    context = context,
                    hiddenApps = hiddenNotiApps,
                    onToggle = { pkg, hidden ->
                        scope.launch {
                            if (hidden) prefs.addHiddenNotiApp(pkg)
                            else prefs.removeHiddenNotiApp(pkg)
                        }
                    }
                )
            }

            Divider(color = FG, thickness = 1.dp)

            // Hidden from Recents
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showHiddenRecentApps = !showHiddenRecentApps }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.VisibilityOff, null, tint = FG, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hidden from Recents", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FG)
                    Text("${hiddenRecentApps.size} apps hidden", fontSize = 12.sp, color = FG)
                }
                Icon(
                    if (showHiddenRecentApps) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = FG, modifier = Modifier.size(20.dp)
                )
            }
            if (showHiddenRecentApps) {
                Divider(color = FG, thickness = 1.dp)
                AppFilterList(
                    context = context,
                    hiddenApps = hiddenRecentApps,
                    onToggle = { pkg, hidden ->
                        scope.launch {
                            if (hidden) prefs.addHiddenRecentApp(pkg)
                            else prefs.removeHiddenRecentApp(pkg)
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Custom Tones
        CustomTonesSection(context)

        Spacer(Modifier.height(28.dp))

        // Support — deliberately last, and deliberately lighter than the cards above.
        //
        // It used to sit second on the screen, above the permissions, in a full-width card
        // identical to the functional rows — so it read as a setting and was the first thing a
        // new user hit before the app even worked. Here it's the closing note instead: a
        // centred, self-contained pill that is still obvious at a glance, but only after
        // everything the user actually came for.
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, BORDER, RoundedCornerShape(20.dp))
                .clickable {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://buymeacoffee.com/screensensitive")
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Favorite, null, tint = FG, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Buy Me a Coffee", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = FG)
        }
        Text(
            "Support KompaktX development",
            fontSize = 11.sp, color = FG,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 6.dp)
        )

        Spacer(Modifier.height(32.dp))
    }
}

/** Notification popup settings: whether they appear, and how long they stay. */
@Composable
private fun HeadsUpScreen(
    enabled: Boolean,
    durationMs: Int,
    queueMode: Boolean,
    onToggle: (Boolean) -> Unit,
    onDuration: (Int) -> Unit,
    onQueueMode: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onBack).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ChevronLeft, null, tint = FG, modifier = Modifier.size(22.dp))
            Text("Back", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = FG)
        }
        Spacer(Modifier.height(12.dp))
        Text("Notification Popups", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = FG)
        Text(
            "A popup appears at the top of the screen when a notification arrives.",
            fontSize = 13.sp, color = FG, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
        )

        SettingsCard {
            ToggleRow("Show Popups", "Appear over whatever you are using",
                Icons.Default.NotificationsActive, enabled) { onToggle(it) }
        }

        if (enabled) {
            Spacer(Modifier.height(20.dp))
            SectionHeader("Time on screen")
            SettingsCard {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(2000 to "2s", 3000 to "3s", 5000 to "5s", 8000 to "8s", 10000 to "10s")
                            .forEach { (ms, label) ->
                                val selected = durationMs == ms
                                Box(
                                    modifier = Modifier.weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(2.dp, FG, RoundedCornerShape(8.dp))
                                        .then(
                                            if (selected) Modifier.background(FG, RoundedCornerShape(8.dp))
                                            else Modifier
                                        )
                                        .clickable { onDuration(ms) }
                                        .heightIn(min = 44.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        color = if (selected) BG else FG)
                                }
                            }
                    }
                    Text(
                        "Touching a popup pauses this, and opening the reply box stops it entirely.",
                        fontSize = 12.sp, color = FG, modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("When several arrive at once")
            SettingsCard {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(false to "Replace", true to "Queue").forEach { (queue, label) ->
                            val selected = queueMode == queue
                            Box(
                                modifier = Modifier.weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(2.dp, FG, RoundedCornerShape(8.dp))
                                    .then(
                                        if (selected) Modifier.background(FG, RoundedCornerShape(8.dp))
                                        else Modifier
                                    )
                                    .clickable { onQueueMode(queue) }
                                    .heightIn(min = 44.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    color = if (selected) BG else FG)
                            }
                        }
                    }
                    Text(
                        if (queueMode)
                            "Each popup is shown in turn for the full duration. Up to five wait; " +
                                "beyond that the oldest is dropped."
                        else
                            "The newest popup takes the screen straight away, as Android does. " +
                                "Earlier ones are not shown again — they stay in the tray.",
                        fontSize = 12.sp, color = FG, modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Explains why the Location toggle needs a permission the app cannot ask for, and lets it be
 * switched on once that permission exists.
 *
 * The switch is disabled rather than hidden while ungranted: hiding it would leave no way to
 * discover the feature or find out what unlocks it.
 */
@Composable
private fun LocationToggleScreen(
    granted: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val command = "adb shell pm grant com.kompaktx.app android.permission.WRITE_SECURE_SETTINGS"
    Column(
        Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onBack)
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ChevronLeft, null, tint = FG, modifier = Modifier.size(22.dp))
            Text("Back", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = FG)
        }
        Spacer(Modifier.height(12.dp))
        Text("Location Toggle", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = FG)
        Text(
            "Adds a Location button to the panel's quick toggles.",
            fontSize = 13.sp, color = FG, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
        )

        SettingsCard {
            // Greyed out until the permission exists — the row still shows so the feature is
            // discoverable, but it cannot be switched on into a state that would not work.
            val rowAlpha = if (granted) 1f else 0.4f
            Row(
                modifier = Modifier.fillMaxWidth()
                    .then(if (granted) Modifier.clickable { onToggle(!enabled) } else Modifier)
                    .padding(16.dp)
                    .alpha(rowAlpha),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LocationOn, null, tint = FG, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Show in panel", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FG)
                    Text(
                        if (granted) "Tap the button to turn location on or off"
                        else "Unavailable until the permission below is granted",
                        fontSize = 12.sp, color = FG
                    )
                }
                Switch(
                    checked = enabled && granted,
                    onCheckedChange = if (granted) { v -> onToggle(v) } else null,
                    enabled = granted,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BG, checkedTrackColor = FG,
                        uncheckedThumbColor = FG, uncheckedTrackColor = BG,
                        uncheckedBorderColor = FG,
                        disabledCheckedTrackColor = FG, disabledUncheckedTrackColor = BG
                    )
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader(if (granted) "Permission granted" else "Permission needed")
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (granted)
                        "WRITE_SECURE_SETTINGS is granted, so KompaktX can switch location on and off directly."
                    else
                        "Android does not let an app turn location on or off without " +
                            "WRITE_SECURE_SETTINGS, and there is no way to request it from inside " +
                            "the app. It has to be granted once over adb — the grant then survives " +
                            "reboots and updates.",
                    fontSize = 13.sp, color = FG
                )
                if (!granted) {
                    Spacer(Modifier.height(14.dp))
                    // The command itself is the button — a separate "copy" control next to a
                    // block of text you obviously want to copy is one thing too many on a
                    // screen this narrow.
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, BORDER, RoundedCornerShape(6.dp))
                            .clickable {
                                val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                                clip?.setPrimaryClip(
                                    android.content.ClipData.newPlainText("adb command", command)
                                )
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }
                            .padding(10.dp)
                    ) {
                        Text(command, fontSize = 11.sp, color = FG)
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ContentCopy, null, tint = FG,
                                modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Tap to copy", fontSize = 10.sp, color = FG,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "This screen updates on its own once the permission is granted.",
                        fontSize = 11.sp, color = FG
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}


@Composable
private fun SectionHeader(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = FG,
        modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, BORDER, RoundedCornerShape(12.dp))
            .background(CARD_BG),
        content = content
    )
}

@Composable
private fun PermissionRow(title: String, subtitle: String, icon: ImageVector, isGranted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = !isGranted, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = FG, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FG)
            Text(subtitle, fontSize = 12.sp, color = FG)
        }
        Icon(
            if (isGranted) Icons.Default.Check else Icons.Default.ChevronRight,
            null, tint = FG, modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, icon: ImageVector, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = FG, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FG)
            Text(subtitle, fontSize = 12.sp, color = FG)
        }
        Box(
            modifier = Modifier.size(24.dp)
                .border(2.dp, FG, RoundedCornerShape(4.dp))
                .then(if (checked) Modifier.background(FG, RoundedCornerShape(4.dp)) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Icon(Icons.Default.Check, null, tint = BG, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ShortcutRow(
    action: String,
    label: String,
    description: String,
    icon: ImageVector,
    currentTrigger: Int,
    onTriggerChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = FG, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FG)
                Text(
                    if (currentTrigger == ShortcutTrigger.NONE) description
                    else ShortcutTrigger.label(currentTrigger),
                    fontSize = 12.sp, color = FG
                )
            }
            // Show current assignment or "None"
            Box(
                modifier = Modifier
                    .border(2.dp, FG, RoundedCornerShape(8.dp))
                    .then(
                        if (currentTrigger != ShortcutTrigger.NONE) Modifier.background(FG, RoundedCornerShape(8.dp))
                        else Modifier
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    if (currentTrigger == ShortcutTrigger.NONE) "None"
                    else ShortcutTrigger.label(currentTrigger),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (currentTrigger != ShortcutTrigger.NONE) BG else FG
                )
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp)) {
                // None option
                ShortcutTriggerOption("None", currentTrigger == ShortcutTrigger.NONE) {
                    onTriggerChange(ShortcutTrigger.NONE)
                    expanded = false
                }
                // All trigger options
                ShortcutTrigger.ALL.forEach { trigger ->
                    ShortcutTriggerOption(
                        ShortcutTrigger.label(trigger),
                        currentTrigger == trigger
                    ) {
                        onTriggerChange(trigger)
                        expanded = false
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutTriggerOption(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(18.dp)
                .border(2.dp, FG, RoundedCornerShape(9.dp))
                .then(
                    if (isSelected) Modifier.padding(3.dp).background(FG, RoundedCornerShape(6.dp))
                    else Modifier
                )
        )
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, color = FG)
    }
}

@Composable
private fun TabPositionPicker(currentPos: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VerticalAlignTop, null, tint = FG, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Tab Position", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FG)
                Text(
                    if (currentPos == TabPosition.TOP) "Tabs at the top of the panel"
                    else "Tabs at the bottom of the panel",
                    fontSize = 12.sp, color = FG
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            data class PosOpt(val label: String, val pos: Int)
            val opts = listOf(
                PosOpt("Top", TabPosition.TOP),
                PosOpt("Bottom", TabPosition.BOTTOM)
            )
            opts.forEach { opt ->
                val isSelected = currentPos == opt.pos
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .border(2.dp, FG, RoundedCornerShape(8.dp))
                        .then(if (isSelected) Modifier.background(FG, RoundedCornerShape(8.dp)) else Modifier)
                        .clickable { onSelect(opt.pos) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(opt.label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (isSelected) BG else FG)
                }
            }
        }
    }
}

@Composable
private fun RecentsLayoutPicker(currentMode: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ViewCarousel, null, tint = FG, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Recents Layout", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FG)
                Text(
                    if (currentMode == RecentsLayoutMode.INLINE) "Inline row at the bottom of the panel"
                    else "Tabbed list alongside Notifications and Settings",
                    fontSize = 12.sp, color = FG
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            data class LayoutOpt(val label: String, val mode: Int)
            val opts = listOf(
                LayoutOpt("Inline", RecentsLayoutMode.INLINE),
                LayoutOpt("Tabbed", RecentsLayoutMode.TABBED)
            )
            opts.forEach { opt ->
                val isSelected = currentMode == opt.mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .border(2.dp, FG, RoundedCornerShape(8.dp))
                        .then(if (isSelected) Modifier.background(FG, RoundedCornerShape(8.dp)) else Modifier)
                        .clickable { onSelect(opt.mode) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(opt.label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (isSelected) BG else FG)
                }
            }
        }
    }
}

@Composable
private fun TriggerOption(label: String, mode: Int, currentMode: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = FG, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier.size(22.dp)
                .border(2.dp, FG, RoundedCornerShape(11.dp))
                .then(
                    if (mode == currentMode) Modifier.padding(4.dp).background(FG, RoundedCornerShape(7.dp))
                    else Modifier
                )
        )
    }
}

@Composable
private fun HowToStep(number: String, text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(24.dp).border(2.dp, FG, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) { Text(number, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FG) }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = FG, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun AppFilterList(context: Context, hiddenApps: Set<String>, onToggle: (String, Boolean) -> Unit) {
    val installedApps = remember {
        context.packageManager.getInstalledApplications(0)
            .filter { app ->
                context.packageManager.getLaunchIntentForPackage(app.packageName) != null
            }
            .sortedBy {
                context.packageManager.getApplicationLabel(it).toString().lowercase()
            }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        installedApps.forEach { app ->
            val isHidden = app.packageName in hiddenApps
            val label = context.packageManager.getApplicationLabel(app).toString()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(app.packageName, !isHidden) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, fontSize = 13.sp, color = FG, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier.size(20.dp)
                        .border(2.dp, FG, RoundedCornerShape(4.dp))
                        .then(if (isHidden) Modifier.background(FG, RoundedCornerShape(4.dp)) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (isHidden) Icon(Icons.Default.Check, null, tint = BG, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun CustomTonesSection(context: Context) {
    var selectedToneType by remember { mutableStateOf(ToneType.RINGTONE) }
    var tones by remember { mutableStateOf<List<ToneInfo>>(emptyList()) }
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var currentToneName by remember { mutableStateOf("") }
    var playingUri by remember { mutableStateOf<Uri?>(null) }
    var showTones by remember { mutableStateOf(false) }

    // Refresh tone list
    fun refreshTones() {
        tones = ToneManager.listTones(context, selectedToneType)
        currentUri = ToneManager.getCurrentTone(context, selectedToneType)
        currentToneName = ToneManager.getToneName(context, currentUri)
    }

    LaunchedEffect(selectedToneType) { refreshTones() }

    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }

    // File picker for importing custom tones — OpenDocument gives a proper content URI on all file managers
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Take persistable permission so the URI stays readable on background thread
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val displayName = getFileName(context, uri) ?: "custom_tone"
            importing = true
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val imported = ToneManager.importTone(context, uri, displayName, selectedToneType)
                val set = if (imported != null) ToneManager.setTone(context, selectedToneType, imported) else false
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    importing = false
                    when {
                        imported == null -> Toast.makeText(context, "Failed to import — check the file format", Toast.LENGTH_LONG).show()
                        !set -> {
                            Toast.makeText(context, "Imported but couldn't set — tap it in the list", Toast.LENGTH_LONG).show()
                            refreshTones()
                        }
                        else -> {
                            currentUri = imported
                            currentToneName = displayName
                            refreshTones()
                            Toast.makeText(context, "Set: $displayName", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    SectionHeader("Custom Tones")

    SettingsCard {
        // Tone type selector
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ToneType.values().forEach { type ->
                val isSelected = type == selectedToneType
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .border(2.dp, FG, RoundedCornerShape(8.dp))
                        .then(
                            if (isSelected) Modifier.background(FG, RoundedCornerShape(8.dp))
                            else Modifier
                        )
                        .clickable { selectedToneType = type; ToneManager.stopPreview(); playingUri = null }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        type.name.lowercase().replaceFirstChar { it.uppercase() },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) BG else FG
                    )
                }
            }
        }

        Divider(color = FG, thickness = 1.dp)

        // Import button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !importing) { filePicker.launch(arrayOf("audio/*")) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (importing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = FG)
            } else {
                Icon(Icons.Default.Add, null, tint = FG, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (importing) "Importing…"
                else "Import Custom ${selectedToneType.name.lowercase().replaceFirstChar { it.uppercase() }}",
                fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FG
            )
        }

        Divider(color = FG, thickness = 1.dp)

        // Current tone
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showTones = !showTones }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (selectedToneType) {
                    ToneType.RINGTONE -> Icons.Default.RingVolume
                    ToneType.NOTIFICATION -> Icons.Default.NotificationsActive
                    ToneType.ALARM -> Icons.Default.Alarm
                },
                null, tint = FG, modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Current ${selectedToneType.name.lowercase()}", fontSize = 12.sp, color = FG)
                Text(
                    currentToneName.ifEmpty { "None" },
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FG
                )
            }
            if (currentUri != null) {
                val isCurrentPlaying = ToneManager.sameUri(playingUri, currentUri) && ToneManager.isPlaying()
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .border(2.dp, FG, RoundedCornerShape(14.dp))
                        .clickable(onClick = {
                            if (isCurrentPlaying) {
                                ToneManager.stopPreview(); playingUri = null
                            } else {
                                ToneManager.playPreview(context, currentUri!!); playingUri = currentUri
                            }
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isCurrentPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        null, tint = FG, modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                if (showTones) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, tint = FG, modifier = Modifier.size(20.dp)
            )
        }

        // Tone list
        if (showTones) {
            Divider(color = FG, thickness = 1.dp)
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                // Silent option
                ToneRow(
                    title = "Silent",
                    isSelected = currentUri == null,
                    isPlaying = false,
                    onSelect = {
                        if (Settings.System.canWrite(context)) {
                            ToneManager.setTone(context, selectedToneType, null)
                            refreshTones()
                        } else {
                            Toast.makeText(context, "Grant 'Modify System Settings' first", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onPlay = {}
                )

                tones.forEach { tone ->
                    ToneRow(
                        title = tone.title,
                        isSelected = ToneManager.sameUri(tone.uri, currentUri),
                        isPlaying = ToneManager.sameUri(playingUri, tone.uri) && ToneManager.isPlaying(),
                        onSelect = {
                            if (Settings.System.canWrite(context)) {
                                val ok = ToneManager.setTone(context, selectedToneType, tone.uri)
                                if (ok) {
                                    currentUri = tone.uri
                                    currentToneName = tone.title
                                    refreshTones()
                                    Toast.makeText(context, "Set: ${tone.title}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed to set tone", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Grant 'Modify System Settings' first", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onPlay = {
                            if (ToneManager.sameUri(playingUri, tone.uri) && ToneManager.isPlaying()) {
                                ToneManager.stopPreview()
                                playingUri = null
                            } else {
                                ToneManager.playPreview(context, tone.uri)
                                playingUri = tone.uri
                            }
                        }
                    )
                }
            }
        }
    }

    // Stop playback when leaving section
    DisposableEffect(Unit) {
        onDispose { ToneManager.stopPreview() }
    }
}

@Composable
private fun ToneRow(
    title: String,
    isSelected: Boolean,
    isPlaying: Boolean,
    onSelect: () -> Unit,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Selection indicator
        Box(
            modifier = Modifier.size(20.dp)
                .border(2.dp, FG, RoundedCornerShape(10.dp))
                .then(
                    if (isSelected) Modifier.padding(4.dp).background(FG, RoundedCornerShape(6.dp))
                    else Modifier
                )
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            fontSize = 13.sp,
            color = FG,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        // Play/stop button
        if (title != "Silent") {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .border(2.dp, FG, RoundedCornerShape(14.dp))
                    .clickable { onPlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    null, tint = FG, modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String? {
    var name: String? = null
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
    } catch (_: Exception) {}
    return name?.substringBeforeLast('.') ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
}

private fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@android.annotation.SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
