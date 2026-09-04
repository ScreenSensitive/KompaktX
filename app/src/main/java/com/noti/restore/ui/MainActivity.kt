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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
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
        // Seed the theme before the first frame — see themeCache below.
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

    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }

    val hasOverlayPermission = remember(tick) { Settings.canDrawOverlays(context) }
    val hasNotificationAccess = remember(tick) { KompaktXListener.isNotificationAccessGranted(context) }
    val hasUsageAccess = remember(tick) { hasUsageStatsPermission(context) }
    val hasAccessibility = remember(tick) { RecentsButtonService.isRunning }
    val hasBatteryOptExemption = remember(tick) { isIgnoringBatteryOptimizations(context) }
    val hasWriteSettings = remember(tick) { Settings.System.canWrite(context) }
    val isServiceRunning = remember(tick) { KompaktXListener.isEnabled() }

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
    val volumeLongPressMedia by prefs.volumeLongPressMedia.collectAsState(initial = false)

    LaunchedEffect(headsUpEnabled) { OverlayPanelManager.headsUpEnabled = headsUpEnabled }
    LaunchedEffect(recentAppsEnabled) { OverlayPanelManager.recentAppsEnabled = recentAppsEnabled }
    LaunchedEffect(triggerMode) {
        OverlayPanelManager.triggerMode = triggerMode
        OverlayPanelManager.recreateTouchStrip()
    }
    LaunchedEffect(fontSizeOffset) { OverlayPanelManager.fontSizeOffset = fontSizeOffset }
    LaunchedEffect(einkControlsEnabled) { OverlayPanelManager.einkControlsEnabled = einkControlsEnabled }
    LaunchedEffect(shortcutFrontLight, shortcutEinkRefresh) {
        RecentsButtonService.updateShortcuts(shortcutFrontLight, shortcutEinkRefresh, ShortcutTrigger.NONE)
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
    var showHiddenRecentApps by remember { mutableStateOf(false) }

    // Hold the (correctly themed) empty screen until the stored preferences arrive. Every toggle
    // below is collected with a hardcoded default as its pre-DataStore value, so rendering now
    // would briefly show a screen full of defaults — features off, trigger unset — and then snap
    // to the user's real configuration. A blank beat is far less jarring than showing settings
    // that were never theirs.
    if (!prefsLoaded) {
        Box(Modifier.fillMaxSize().background(BG))
        return
    }

    // ── Navigation ──────────────────────────────────────────────────────────────
    // Two levels, never three: the index and one detail screen. Anything that would need a
    // third level (shortcut triggers, the app filter lists) expands in place instead — on a
    // display this slow, a wrong turn costs a full redraw twice.
    var screen by remember { mutableStateOf(SettingsScreen.INDEX) }
    BackHandler(enabled = screen != SettingsScreen.INDEX) { screen = SettingsScreen.INDEX }
    val onBack: () -> Unit = { screen = SettingsScreen.INDEX }

    // Which permissions are still outstanding, by the names used on the Permissions screen.
    val missingPermissions = buildList {
        if (!hasNotificationAccess) add("Notification Access")
        if (!hasOverlayPermission) add("Display Over Other Apps")
        if (!hasUsageAccess) add("Usage Access")
        if (!hasAccessibility) add("Accessibility Service")
        if (!hasBatteryOptExemption) add("Disable Battery Optimization")
        if (!hasWriteSettings) add("Modify System Settings")
    }

    // Reading a tone's display name touches the media store, so resolve it only when the index
    // is actually shown rather than on every recomposition of a 2-second status tick.
    val toneSummary = remember(screen) {
        if (screen != SettingsScreen.INDEX) "" else try {
            ToneManager.getToneName(context, ToneManager.getCurrentTone(context, ToneType.NOTIFICATION))
        } catch (_: Exception) { "System default" }
    }

    when (screen) {

    // ── Index ───────────────────────────────────────────────────────────────────
    SettingsScreen.INDEX ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("KompaktX", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = FG)
        Text(
            if (allPermissionsGranted && isServiceRunning)
                "Active · ${getTriggerDescription(triggerMode).replaceFirstChar { it.lowercase() }}"
            else "Setup required · ${missingPermissions.size} " +
                (if (missingPermissions.size == 1) "permission" else "permissions") + " missing",
            fontSize = 13.sp, color = FG,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
        )

        // The only inverted element in the app, and only while something is blocking it.
        if (missingPermissions.isNotEmpty()) {
            SetupBanner(missingPermissions) { screen = SettingsScreen.PERMISSIONS }
            Spacer(Modifier.height(16.dp))
        }

        Divider(color = FG, thickness = 1.dp)
        IndexRow("Notification Tray Shortcut", getTriggerDescription(triggerMode)) {
            screen = SettingsScreen.TRIGGER
        }
        IndexRow("Features", buildList {
            add(if (headsUpEnabled) "Heads-up on" else "Heads-up off")
            if (recentAppsEnabled)
                add(if (recentsLayoutMode == RecentsLayoutMode.TABBED) "Recents tabbed" else "Recents inline")
            else add("Recents off")
            add(if (hideAppIcons) "Icons hidden" else "Icons shown")
        }.joinToString(" · ")) { screen = SettingsScreen.FEATURES }
        IndexRow("Shortcuts", buildList {
            if (shortcutFrontLight != ShortcutTrigger.NONE)
                add("${ShortcutTrigger.label(shortcutFrontLight)} → Front Light")
            if (shortcutEinkRefresh != ShortcutTrigger.NONE)
                add("${ShortcutTrigger.label(shortcutEinkRefresh)} → E-Ink Refresh")
            if (volumeLongPressMedia) add("Volume hold → Media skip")
        }.ifEmpty { listOf("None assigned") }.joinToString(" · ")) {
            screen = SettingsScreen.SHORTCUTS
        }
        IndexRow("Theme", listOf(
            when (themeMode) {
                ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark"; else -> "System"
            },
            if (tabPosition == com.noti.restore.ui.TabPosition.BOTTOM) "Tabs at bottom" else "Tabs at top"
        ).joinToString(" · ")) { screen = SettingsScreen.THEME }
        IndexRow("Font Size", when (fontSizeOffset) {
            0 -> "Small"; 4 -> "Large"; else -> "Medium"
        }) { screen = SettingsScreen.FONT_SIZE }
        IndexRow("App Filters",
            if (hiddenNotiApps.isEmpty() && hiddenRecentApps.isEmpty()) "None hidden"
            else "${hiddenNotiApps.size} from notifications · ${hiddenRecentApps.size} from recents"
        ) { screen = SettingsScreen.APP_FILTERS }
        IndexRow("Custom Tones", "Notification · $toneSummary") { screen = SettingsScreen.TONES }
        IndexRow("Permissions",
            if (missingPermissions.isEmpty()) "All 6 granted"
            else "${6 - missingPermissions.size} of 6 granted"
        ) { screen = SettingsScreen.PERMISSIONS }

        Spacer(Modifier.height(26.dp))


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

    // ── Notification Tray Shortcut ──────────────────────────────────────────────
    SettingsScreen.TRIGGER -> DetailScreen("Notification Tray Shortcut", "How you open the panel", onBack) {
        Divider(color = FG, thickness = 1.dp)
        ChoiceOption("Swipe right from the top-left edge", null,
            triggerMode == TriggerMode.SWIPE_LEFT_EDGE) {
            scope.launch { prefs.setTriggerMode(TriggerMode.SWIPE_LEFT_EDGE) } }
        ChoiceOption("Swipe left from the top-right edge", null,
            triggerMode == TriggerMode.SWIPE_RIGHT_EDGE) {
            scope.launch { prefs.setTriggerMode(TriggerMode.SWIPE_RIGHT_EDGE) } }
        ChoiceOption("Pull up from the bottom-right edge", null,
            triggerMode == TriggerMode.PULL_UP_BOTTOM_RIGHT) {
            scope.launch { prefs.setTriggerMode(TriggerMode.PULL_UP_BOTTOM_RIGHT) } }
        ChoiceOption("Pull up from the bottom-left edge", null,
            triggerMode == TriggerMode.PULL_UP_BOTTOM_LEFT) {
            scope.launch { prefs.setTriggerMode(TriggerMode.PULL_UP_BOTTOM_LEFT) } }
        // Kept visible with its dependency spelled out rather than hidden when Accessibility is
        // off — a missing option is more confusing than an explained one.
        ChoiceOption("Recents button",
            if (hasAccessibility) "Uses the Accessibility Service" else "Needs Accessibility Service",
            triggerMode == TriggerMode.RECENTS_BUTTON) {
            scope.launch { prefs.setTriggerMode(TriggerMode.RECENTS_BUTTON) } }
        Spacer(Modifier.height(14.dp))
        Text("Changing this takes effect immediately", fontSize = 12.sp, color = FG)
    }

    // ── Features ────────────────────────────────────────────────────────────────
    SettingsScreen.FEATURES -> DetailScreen("Features", null, onBack) {
        Divider(color = FG, thickness = 1.dp)
        StateRow("Heads-Up Notifications", "Show popup for new notifications",
            if (headsUpEnabled) "ON" else "OFF") {
            scope.launch { prefs.setHeadsUpEnabled(!headsUpEnabled) } }
        StateRow("Recent Apps", "Show recently used apps in panel",
            if (recentAppsEnabled) "ON" else "OFF") {
            scope.launch { prefs.setRecentAppsEnabled(!recentAppsEnabled) } }
        StateRow("Hide App Icons", "Hide icons in recents and notifications",
            if (hideAppIcons) "ON" else "OFF") {
            scope.launch { prefs.setHideAppIcons(!hideAppIcons) } }
        // A dash rather than a disabled-looking switch when the device has no e-ink controller.
        StateRow("E-Ink Controls",
            if (MeinkController.isAvailable) "Show display mode buttons in panel"
            else "Not available on this device",
            if (!MeinkController.isAvailable) "—" else if (einkControlsEnabled) "ON" else "OFF",
            enabled = MeinkController.isAvailable) {
            scope.launch { prefs.setEinkControlsEnabled(!einkControlsEnabled) } }

        if (recentAppsEnabled) {
            FieldLabel("Recents Layout")
            Segmented(listOf("Inline" to RecentsLayoutMode.INLINE, "Tabbed" to RecentsLayoutMode.TABBED),
                recentsLayoutMode) { scope.launch { prefs.setRecentsLayoutMode(it) } }
            Text(
                if (recentsLayoutMode == RecentsLayoutMode.INLINE)
                    "Inline row at the bottom of the panel"
                else "Tabbed list alongside Notifications and Settings",
                fontSize = 12.sp, color = FG, modifier = Modifier.padding(top = 6.dp)
            )
        }
    }

    // ── Shortcuts ───────────────────────────────────────────────────────────────
    SettingsScreen.SHORTCUTS -> DetailScreen("Shortcuts", "Hardware buttons", onBack) {
        Text(
            "Each trigger can only be used once — reassigning it moves it off the other action. " +
                "Requires the Accessibility Service.",
            fontSize = 12.sp, color = FG, modifier = Modifier.padding(bottom = 10.dp)
        )
        Divider(color = FG, thickness = 1.dp)
        // Expanded in place rather than pushing a third level: assigning a trigger is a
        // two-second decision and a navigation would cost more than it saves.
        ShortcutAssignment("Front Light Toggle", "Toggle front light on/off",
            shortcutFrontLight, shortcutEinkRefresh) {
            scope.launch { prefs.setShortcutTrigger("front_light", it) } }
        ShortcutAssignment("E-Ink Refresh", "Flash black/white to clear ghosting",
            shortcutEinkRefresh, shortcutFrontLight) {
            scope.launch { prefs.setShortcutTrigger("eink_refresh", it) } }
        StateRow("Volume Long Press → Media Skip",
            "Long press Vol Up/Down to skip or go back, only while media is playing",
            if (volumeLongPressMedia) "ON" else "OFF") {
            scope.launch { prefs.setVolumeLongPressMedia(!volumeLongPressMedia) } }
    }

    // ── Theme ───────────────────────────────────────────────────────────────────
    SettingsScreen.THEME -> DetailScreen("Theme", null, onBack) {
        FieldLabel("Appearance")
        Segmented(listOf("System" to ThemeMode.SYSTEM, "Light" to ThemeMode.LIGHT,
            "Dark" to ThemeMode.DARK), themeMode) { scope.launch { prefs.setThemeMode(it) } }
        Text("Follows the system setting when set to System",
            fontSize = 12.sp, color = FG, modifier = Modifier.padding(top = 6.dp))

        FieldLabel("Tab Position")
        Segmented(listOf("Top" to TabPosition.TOP, "Bottom" to TabPosition.BOTTOM),
            tabPosition) { scope.launch { prefs.setTabPosition(it) } }
        Text("Where the panel's tab strip sits",
            fontSize = 12.sp, color = FG, modifier = Modifier.padding(top = 6.dp))

        // These change the overlay, not this screen — without a preview you would have to close
        // the app to judge them.
        FieldLabel("Preview")
        PanelPreview(tabPosition, fontSizeOffset)
    }

    // ── Font Size ───────────────────────────────────────────────────────────────
    SettingsScreen.FONT_SIZE -> DetailScreen("Font Size", null, onBack) {
        FieldLabel("Size")
        Segmented(listOf("Small" to 0, "Medium" to 2, "Large" to 4), fontSizeOffset) {
            scope.launch { prefs.setFontSizeOffset(it) } }
        FieldLabel("Preview")
        PanelPreview(tabPosition, fontSizeOffset)
        Text("Affects the panel, not this settings screen",
            fontSize = 12.sp, color = FG, modifier = Modifier.padding(top = 8.dp))
    }

    // ── App Filters ─────────────────────────────────────────────────────────────
    SettingsScreen.APP_FILTERS -> DetailScreen("App Filters", null, onBack) {
        Divider(color = FG, thickness = 1.dp)
        StateRow("Hidden from Notifications", "${hiddenNotiApps.size} apps hidden",
            if (showHiddenNotiApps) "▲" else "▼") { showHiddenNotiApps = !showHiddenNotiApps }
        if (showHiddenNotiApps) {
            AppFilterList(context, hiddenNotiApps) { pkg, hidden ->
                scope.launch {
                    if (hidden) prefs.addHiddenNotiApp(pkg) else prefs.removeHiddenNotiApp(pkg)
                }
            }
            Divider(color = FG, thickness = 1.dp)
        }
        StateRow("Hidden from Recents", "${hiddenRecentApps.size} apps hidden",
            if (showHiddenRecentApps) "▲" else "▼") { showHiddenRecentApps = !showHiddenRecentApps }
        if (showHiddenRecentApps) {
            AppFilterList(context, hiddenRecentApps) { pkg, hidden ->
                scope.launch {
                    if (hidden) prefs.addHiddenRecentApp(pkg) else prefs.removeHiddenRecentApp(pkg)
                }
            }
            Divider(color = FG, thickness = 1.dp)
        }
    }

    // ── Custom Tones ────────────────────────────────────────────────────────────
    SettingsScreen.TONES -> DetailScreen("Custom Tones", null, onBack) {
        CustomTonesSection(context)
    }

    // ── Permissions ─────────────────────────────────────────────────────────────
    SettingsScreen.PERMISSIONS -> DetailScreen("Permissions",
        "${6 - missingPermissions.size} of 6 granted", onBack) {
        Divider(color = FG, thickness = 1.dp)
        // Granted permissions stay listed rather than disappearing: this screen exists to answer
        // "what have I given this app", and a row you cannot find is one you cannot verify.
        PermissionEntry("Notification Access", "Required to read notifications", hasNotificationAccess) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        PermissionEntry("Display Over Other Apps", "Required for notification panel", hasOverlayPermission) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:${context.packageName}") }) }
        PermissionEntry("Usage Access", "Required for recent apps", hasUsageAccess) {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        PermissionEntry("Accessibility Service", "Required for Recents button trigger", hasAccessibility) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        PermissionEntry("Disable Battery Optimization",
            "Prevents the system from killing the overlay", hasBatteryOptExemption) {
            requestIgnoreBatteryOptimizations(context) }
        PermissionEntry("Modify System Settings",
            "Required for brightness, adaptive brightness, and custom tones", hasWriteSettings) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}") }) }
    }

    } // when (screen)
}

/** The settings app is two levels deep: this index, and one detail screen at a time. */
private enum class SettingsScreen {
    INDEX, TRIGGER, FEATURES, SHORTCUTS, THEME, FONT_SIZE, APP_FILTERS, TONES, PERMISSIONS
}

// ─── Settings components ────────────────────────────────────────────────────────
// Five parts, fixed once here so eight detail screens don't each invent their own conventions.
// With only two tones available that temptation is high, because the usual signals — colour,
// shadow, a switch that animates between states — simply aren't there.

/** Minimum comfortable touch target. 44 dp is 7.0 mm on this 217 ppi panel. */
private val TAP = 44.dp

/** Back bar, title, and a scrolling body. Every detail screen is this shape. */
@Composable
private fun DetailScreen(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().background(BG)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onBack)
                .heightIn(min = TAP).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ChevronLeft, null, tint = FG, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(2.dp))
            Text("Settings", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = FG)
        }
        Divider(color = FG, thickness = 1.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = FG)
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, color = FG, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(12.dp))
            content()
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** A row on the index: name, the value it is currently set to, and a chevron. */
@Composable
private fun IndexRow(name: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .heightIn(min = TAP).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = FG)
            Text(value, fontSize = 12.sp, color = FG, modifier = Modifier.padding(top = 1.dp))
        }
        Icon(Icons.Default.ChevronRight, null, tint = FG, modifier = Modifier.size(20.dp))
    }
    Divider(color = FG, thickness = 1.dp)
}

/**
 * A setting whose state is a word — ON, OFF, GRANTED, "—".
 *
 * Deliberately not a switch: a drawn switch carries its state in fill and position, which with
 * two tones and no animation between them is a guess. A word is not.
 */
@Composable
private fun StateRow(
    name: String,
    description: String,
    state: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = TAP).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = FG)
            Text(description, fontSize = 12.sp, color = FG, modifier = Modifier.padding(top = 1.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(state, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = FG)
    }
    Divider(color = FG, thickness = 1.dp)
}

/**
 * One option in a list of mutually exclusive choices.
 *
 * The mark is a filled square rather than a dot: a thin ring at this density can disappear into
 * the panel's texture, and a square edge survives it.
 */
@Composable
private fun ChoiceOption(label: String, description: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .heightIn(min = TAP).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(16.dp).border(2.dp, FG), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(8.dp).background(FG))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = FG)
            if (description != null) {
                Text(description, fontSize = 12.sp, color = FG, modifier = Modifier.padding(top = 1.dp))
            }
        }
    }
    Divider(color = FG, thickness = 1.dp)
}

/** Two or three exclusive options that fit on one line. The only component that inverts. */
@Composable
private fun Segmented(options: List<Pair<String, Int>>, current: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (label, value) ->
            val selected = current == value
            Box(
                modifier = Modifier.weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(2.dp, FG, RoundedCornerShape(8.dp))
                    .then(if (selected) Modifier.background(FG, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { onSelect(value) }
                    .heightIn(min = TAP),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (selected) BG else FG)
            }
        }
    }
}

/** Small caps label above a control group. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = FG,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(top = 20.dp, bottom = 7.dp)
    )
}

/**
 * The one inverted element in the app, shown only while a permission is missing.
 *
 * Inversion is the strongest signal two tones can produce, so it is spent on the single thing
 * that stops the app working — and it names the missing permissions rather than only counting
 * them, since "2 left" makes you open a screen to find out what it means.
 */
@Composable
private fun SetupBanner(missing: List<String>, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(FG, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Text("FINISH SETUP · ${missing.size} LEFT", fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = BG, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(5.dp))
        Text(missing.joinToString(", "), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = BG)
        Text(
            if (missing.size == 1) "Nothing works until this is granted"
            else "Nothing works until these are granted",
            fontSize = 12.sp, color = BG, modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** A permission, always listed — granted ones show their state instead of vanishing. */
@Composable
private fun PermissionEntry(name: String, description: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (granted) Modifier else Modifier.clickable(onClick = onGrant))
            .heightIn(min = TAP).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = FG)
            Text(description, fontSize = 12.sp, color = FG, modifier = Modifier.padding(top = 1.dp))
        }
        Spacer(Modifier.width(10.dp))
        if (granted) {
            Text("GRANTED", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FG)
        } else {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(FG, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Grant", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BG)
            }
        }
    }
    Divider(color = FG, thickness = 1.dp)
}

/**
 * A hardware-shortcut action and its trigger, expanded in place.
 *
 * [takenBy] is the trigger the *other* action currently holds, so a clash can be labelled rather
 * than silently stealing the trigger away from it.
 */
@Composable
private fun ShortcutAssignment(
    name: String,
    description: String,
    current: Int,
    takenBy: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    StateRow(
        name, description,
        if (current == ShortcutTrigger.NONE) "NONE" else ShortcutTrigger.label(current).uppercase()
    ) { expanded = !expanded }
    if (expanded) {
        Column(Modifier.padding(start = 12.dp)) {
            ChoiceOption("None", null, current == ShortcutTrigger.NONE) {
                onSelect(ShortcutTrigger.NONE); expanded = false
            }
            ShortcutTrigger.ALL.forEach { trigger ->
                ChoiceOption(
                    ShortcutTrigger.label(trigger),
                    if (trigger == takenBy) "Currently used by another shortcut" else null,
                    current == trigger
                ) { onSelect(trigger); expanded = false }
            }
        }
    }
}

/**
 * A miniature of the overlay panel, honouring the chosen tab position and font offset.
 *
 * Theme and Font Size are the only settings whose result is invisible from inside the settings
 * app, so they are the only ones that get a preview.
 */
@Composable
private fun PanelPreview(tabPosition: Int, fontSizeOffset: Int) {
    val base = 11 + fontSizeOffset
    val tabs = @Composable {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            listOf("Recents" to false, "Notifications" to true, "Settings" to false)
                .forEach { (label, selected) ->
                    Box(
                        Modifier.padding(horizontal = 3.dp)
                            .clip(RoundedCornerShape(100.dp))
                            .then(
                                if (selected) Modifier.background(FG, RoundedCornerShape(100.dp))
                                else Modifier.border(1.dp, FG, RoundedCornerShape(100.dp))
                            )
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(label, fontSize = (base - 2).sp, fontWeight = FontWeight.Bold,
                            color = if (selected) BG else FG)
                    }
                }
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .border(2.dp, FG, RoundedCornerShape(10.dp)).padding(10.dp)
    ) {
        Text("Thu 04 Sep · 14:18", fontSize = (base - 2).sp, color = FG)
        Divider(color = FG, thickness = 1.dp, modifier = Modifier.padding(vertical = 6.dp))
        if (tabPosition == TabPosition.TOP) { tabs(); Spacer(Modifier.height(4.dp)) }
        Text("Sam Reeve", fontSize = base.sp, fontWeight = FontWeight.Bold, color = FG)
        Text("Running late, start without me", fontSize = (base - 1).sp, color = FG)
        Spacer(Modifier.height(6.dp))
        Text("Standup", fontSize = base.sp, fontWeight = FontWeight.Bold, color = FG)
        Text("15:00 · in 42 minutes", fontSize = (base - 1).sp, color = FG)
        if (tabPosition == TabPosition.BOTTOM) { Spacer(Modifier.height(4.dp)); tabs() }
    }
}

private fun getTriggerDescription(mode: Int): String = when (mode) {
    TriggerMode.SWIPE_LEFT_EDGE -> "Swipe right from the top-left edge"
    TriggerMode.SWIPE_RIGHT_EDGE -> "Swipe left from the top-right edge"
    TriggerMode.PULL_UP_BOTTOM_RIGHT -> "Pull up from the bottom-right edge"
    TriggerMode.PULL_UP_BOTTOM_LEFT -> "Pull up from the bottom-left edge"
    TriggerMode.RECENTS_BUTTON -> "Press the Recents button"
    else -> "Swipe to open panel"
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
