package com.noti.restore.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kompaktx_prefs")

object TriggerMode {
    const val SWIPE_LEFT_EDGE = 0
    const val SWIPE_RIGHT_EDGE = 1
    const val PULL_UP_BOTTOM_RIGHT = 2
    const val PULL_UP_BOTTOM_LEFT = 3
    const val RECENTS_BUTTON = 4
    /** No trigger at all — the panel cannot be opened by gesture or button. */
    const val NONE = 5
}

/** Which physical gesture triggers a shortcut action */
object ShortcutTrigger {
    const val NONE = 0
    const val LONG_PRESS_RECENTS = 1
    const val DOUBLE_TAP_RECENTS = 2
    const val LONG_PRESS_BACK = 3
    const val DOUBLE_TAP_BACK = 4
    const val VOLUME_UP = 5
    const val VOLUME_DOWN = 6

    fun label(t: Int): String = when (t) {
        LONG_PRESS_RECENTS -> "Long Press Recents"
        DOUBLE_TAP_RECENTS -> "Double Tap Recents"
        LONG_PRESS_BACK -> "Long Press Back"
        DOUBLE_TAP_BACK -> "Double Tap Back"
        VOLUME_UP -> "Volume Up"
        VOLUME_DOWN -> "Volume Down"
        else -> "None"
    }

    /** Triggers offered for the always-on shortcuts. */
    val ALL = intArrayOf(LONG_PRESS_RECENTS, DOUBLE_TAP_RECENTS, LONG_PRESS_BACK, DOUBLE_TAP_BACK)

    /**
     * Triggers offered for Reply to Popup, which may also claim the volume keys.
     *
     * Volume is deliberately absent from [ALL]: an always-on shortcut bound to it would swallow
     * volume control entirely, whereas Reply to Popup only claims a key while a repliable popup is
     * on screen and hands it straight back afterwards.
     */
    val POPUP_ALL = intArrayOf(
        LONG_PRESS_RECENTS, DOUBLE_TAP_RECENTS, LONG_PRESS_BACK, DOUBLE_TAP_BACK,
        VOLUME_UP, VOLUME_DOWN
    )
}

object RecentsLayoutMode {
    const val INLINE = 0
    const val TABBED = 1
}

object ThemeMode {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

/** How the panel header renders the time */
object ClockStyle {
    const val TEXT = 0
    const val LARGE = 1
    const val DOTS = 2

    fun label(style: Int): String = when (style) {
        LARGE -> "Large"
        DOTS -> "Dots"
        else -> "Text"
    }
}

object TabPosition {
    const val TOP = 0
    const val BOTTOM = 1
}

class PreferencesManager(private val context: Context) {

    companion object {
        private val HEADS_UP_ENABLED = booleanPreferencesKey("heads_up_enabled")
        private val RECENT_APPS_ENABLED = booleanPreferencesKey("recent_apps_enabled")
        private val HIDDEN_NOTI_APPS = stringSetPreferencesKey("hidden_noti_apps")
        private val HIDDEN_RECENT_APPS = stringSetPreferencesKey("hidden_recent_apps")
        private val AUTO_START = booleanPreferencesKey("auto_start")
        private val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        private val HEADS_UP_DURATION = intPreferencesKey("heads_up_duration_ms")
        private val REPLY_ON_ENTER = booleanPreferencesKey("reply_on_enter")
        private val SHOW_LOCATION_TOGGLE = booleanPreferencesKey("show_location_toggle")
        private val TRIGGER_MODE = intPreferencesKey("trigger_mode")
        private val FONT_SIZE_OFFSET = intPreferencesKey("font_size_offset")
        private val EINK_CONTROLS_ENABLED = booleanPreferencesKey("eink_controls_enabled")

        // Shortcut triggers — each stores a ShortcutTrigger value (0=none)
        private val SHORTCUT_FRONT_LIGHT = intPreferencesKey("shortcut_front_light")
        private val SHORTCUT_EINK_REFRESH = intPreferencesKey("shortcut_eink_refresh")
        private val SHORTCUT_REFRESH_CYCLE = intPreferencesKey("shortcut_refresh_cycle")
        private val POPUP_REPLY_TRIGGER = intPreferencesKey("popup_reply_trigger")
        // Refresh cycle: which 2 modes to alternate between (EinkMode values)
        private val VOLUME_LONG_PRESS_MEDIA = booleanPreferencesKey("volume_long_press_media")
        private val RECENTS_LAYOUT_MODE = intPreferencesKey("recents_layout_mode")
        private val HIDE_APP_ICONS = booleanPreferencesKey("hide_app_icons")
        private val THEME_MODE = intPreferencesKey("theme_mode")
        private val TAB_POSITION = intPreferencesKey("tab_position")
        private val CLOCK_STYLE = intPreferencesKey("clock_style")

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }

    val headsUpEnabled: Flow<Boolean> = context.dataStore.data.map { it[HEADS_UP_ENABLED] ?: true }
    val recentAppsEnabled: Flow<Boolean> = context.dataStore.data.map { it[RECENT_APPS_ENABLED] ?: true }
    val hiddenNotiApps: Flow<Set<String>> = context.dataStore.data.map { it[HIDDEN_NOTI_APPS] ?: emptySet() }
    val hiddenRecentApps: Flow<Set<String>> = context.dataStore.data.map { it[HIDDEN_RECENT_APPS] ?: emptySet() }
    val autoStart: Flow<Boolean> = context.dataStore.data.map { it[AUTO_START] ?: true }
    val hapticsEnabled: Flow<Boolean> = context.dataStore.data.map { it[HAPTICS_ENABLED] ?: true }
    /** A stored 2s from the older presets folds onto 3s so the picker always has a selection. */
    val headsUpDuration: Flow<Int> = context.dataStore.data.map {
        val v = it[HEADS_UP_DURATION] ?: 5000
        if (v == 2000) 3000 else v
    }

    /** On by default: this device has a physical keyboard, so Enter is the expected send key. */
    val replyOnEnter: Flow<Boolean> = context.dataStore.data.map { it[REPLY_ON_ENTER] ?: true }
    val showLocationToggle: Flow<Boolean> = context.dataStore.data.map { it[SHOW_LOCATION_TOGGLE] ?: false }
    val triggerMode: Flow<Int> = context.dataStore.data.map { it[TRIGGER_MODE] ?: TriggerMode.SWIPE_LEFT_EDGE }
    // These four default to what suits the Kompakt rather than the blandest option. The fallback
    // only applies when the key is absent, and keys are written solely by an actual tap in
    // settings — so anyone who already chose a value keeps it, including where their choice
    // happens to match the old default.
    val fontSizeOffset: Flow<Int> = context.dataStore.data.map { it[FONT_SIZE_OFFSET] ?: 2 }
    val einkControlsEnabled: Flow<Boolean> = context.dataStore.data.map { it[EINK_CONTROLS_ENABLED] ?: true }

    val shortcutFrontLight: Flow<Int> = context.dataStore.data.map { it[SHORTCUT_FRONT_LIGHT] ?: ShortcutTrigger.NONE }
    val shortcutEinkRefresh: Flow<Int> = context.dataStore.data.map { it[SHORTCUT_EINK_REFRESH] ?: ShortcutTrigger.NONE }
    val shortcutRefreshCycle: Flow<Int> = context.dataStore.data.map { it[SHORTCUT_REFRESH_CYCLE] ?: ShortcutTrigger.NONE }

    /**
     * Gesture that opens a popup's reply box while one is on screen.
     *
     * Separate from the shortcut assignments rather than competing with them: it only applies while
     * a repliable popup is showing, so the same gesture can keep its usual job the rest of the time.
     */
    val popupReplyTrigger: Flow<Int> = context.dataStore.data.map { it[POPUP_REPLY_TRIGGER] ?: ShortcutTrigger.NONE }
    val volumeLongPressMedia: Flow<Boolean> = context.dataStore.data.map { it[VOLUME_LONG_PRESS_MEDIA] ?: false }
    val recentsLayoutMode: Flow<Int> = context.dataStore.data.map { it[RECENTS_LAYOUT_MODE] ?: RecentsLayoutMode.TABBED }
    val hideAppIcons: Flow<Boolean> = context.dataStore.data.map { it[HIDE_APP_ICONS] ?: false }
    val themeMode: Flow<Int> = context.dataStore.data.map { it[THEME_MODE] ?: ThemeMode.SYSTEM }
    val tabPosition: Flow<Int> = context.dataStore.data.map { it[TAB_POSITION] ?: TabPosition.BOTTOM }
    val clockStyle: Flow<Int> = context.dataStore.data.map { it[CLOCK_STYLE] ?: ClockStyle.TEXT }

    suspend fun setHeadsUpEnabled(enabled: Boolean) { context.dataStore.edit { it[HEADS_UP_ENABLED] = enabled } }
    suspend fun setRecentAppsEnabled(enabled: Boolean) { context.dataStore.edit { it[RECENT_APPS_ENABLED] = enabled } }
    suspend fun setAutoStart(enabled: Boolean) { context.dataStore.edit { it[AUTO_START] = enabled } }
    suspend fun setHapticsEnabled(enabled: Boolean) { context.dataStore.edit { it[HAPTICS_ENABLED] = enabled } }
    suspend fun setHeadsUpDuration(ms: Int) { context.dataStore.edit { it[HEADS_UP_DURATION] = ms } }
    suspend fun setReplyOnEnter(enabled: Boolean) { context.dataStore.edit { it[REPLY_ON_ENTER] = enabled } }
    suspend fun setShowLocationToggle(enabled: Boolean) { context.dataStore.edit { it[SHOW_LOCATION_TOGGLE] = enabled } }
    suspend fun setTriggerMode(mode: Int) { context.dataStore.edit { it[TRIGGER_MODE] = mode } }
    suspend fun setFontSizeOffset(offset: Int) { context.dataStore.edit { it[FONT_SIZE_OFFSET] = offset.coerceIn(-2, 4) } }
    suspend fun setEinkControlsEnabled(enabled: Boolean) { context.dataStore.edit { it[EINK_CONTROLS_ENABLED] = enabled } }

    suspend fun setVolumeLongPressMedia(enabled: Boolean) { context.dataStore.edit { it[VOLUME_LONG_PRESS_MEDIA] = enabled } }
    suspend fun setRecentsLayoutMode(mode: Int) { context.dataStore.edit { it[RECENTS_LAYOUT_MODE] = mode } }
    suspend fun setHideAppIcons(enabled: Boolean) { context.dataStore.edit { it[HIDE_APP_ICONS] = enabled } }
    suspend fun setThemeMode(mode: Int) { context.dataStore.edit { it[THEME_MODE] = mode } }
    suspend fun setTabPosition(pos: Int) { context.dataStore.edit { it[TAB_POSITION] = pos } }
    suspend fun setClockStyle(style: Int) { context.dataStore.edit { it[CLOCK_STYLE] = style } }

    /**
     * Assign a trigger to an action. Automatically unassigns that trigger from any other action.
     * Pass ShortcutTrigger.NONE to clear the assignment.
     */
    suspend fun setPopupReplyTrigger(trigger: Int) { context.dataStore.edit { it[POPUP_REPLY_TRIGGER] = trigger } }

    suspend fun setShortcutTrigger(action: String, trigger: Int) {
        context.dataStore.edit { prefs ->
            // If assigning a real trigger, unassign it from other actions first
            if (trigger != ShortcutTrigger.NONE) {
                val keys = listOf(
                    "front_light" to SHORTCUT_FRONT_LIGHT,
                    "eink_refresh" to SHORTCUT_EINK_REFRESH,
                    "refresh_cycle" to SHORTCUT_REFRESH_CYCLE
                )
                for ((name, key) in keys) {
                    if (name != action && prefs[key] == trigger) {
                        prefs[key] = ShortcutTrigger.NONE
                    }
                }
            }
            // Set the requested action
            when (action) {
                "front_light" -> prefs[SHORTCUT_FRONT_LIGHT] = trigger
                "eink_refresh" -> prefs[SHORTCUT_EINK_REFRESH] = trigger
                "refresh_cycle" -> prefs[SHORTCUT_REFRESH_CYCLE] = trigger
            }
        }
    }

    suspend fun addHiddenNotiApp(pkg: String) {
        context.dataStore.edit { p -> p[HIDDEN_NOTI_APPS] = (p[HIDDEN_NOTI_APPS] ?: emptySet()) + pkg }
    }
    suspend fun removeHiddenNotiApp(pkg: String) {
        context.dataStore.edit { p -> p[HIDDEN_NOTI_APPS] = (p[HIDDEN_NOTI_APPS] ?: emptySet()) - pkg }
    }
    suspend fun addHiddenRecentApp(pkg: String) {
        context.dataStore.edit { p -> p[HIDDEN_RECENT_APPS] = (p[HIDDEN_RECENT_APPS] ?: emptySet()) + pkg }
    }
    suspend fun removeHiddenRecentApp(pkg: String) {
        context.dataStore.edit { p -> p[HIDDEN_RECENT_APPS] = (p[HIDDEN_RECENT_APPS] ?: emptySet()) - pkg }
    }
}
