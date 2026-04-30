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
}

/** Which physical gesture triggers a shortcut action */
object ShortcutTrigger {
    const val NONE = 0
    const val LONG_PRESS_RECENTS = 1
    const val DOUBLE_TAP_RECENTS = 2
    const val LONG_PRESS_BACK = 3

    fun label(t: Int): String = when (t) {
        LONG_PRESS_RECENTS -> "Long Press Recents"
        DOUBLE_TAP_RECENTS -> "Double Tap Recents"
        LONG_PRESS_BACK -> "Long Press Back"
        else -> "None"
    }

    val ALL = intArrayOf(LONG_PRESS_RECENTS, DOUBLE_TAP_RECENTS, LONG_PRESS_BACK)
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
        private val TRIGGER_MODE = intPreferencesKey("trigger_mode")
        private val FONT_SIZE_OFFSET = intPreferencesKey("font_size_offset")
        private val EINK_CONTROLS_ENABLED = booleanPreferencesKey("eink_controls_enabled")

        // Shortcut triggers — each stores a ShortcutTrigger value (0=none)
        private val SHORTCUT_FRONT_LIGHT = intPreferencesKey("shortcut_front_light")
        private val SHORTCUT_EINK_REFRESH = intPreferencesKey("shortcut_eink_refresh")
        private val SHORTCUT_REFRESH_CYCLE = intPreferencesKey("shortcut_refresh_cycle")
        // Refresh cycle: which 2 modes to alternate between (EinkMode values)
        private val REFRESH_CYCLE_MODE_A = intPreferencesKey("refresh_cycle_mode_a")
        private val REFRESH_CYCLE_MODE_B = intPreferencesKey("refresh_cycle_mode_b")
        private val VOLUME_LONG_PRESS_MEDIA = booleanPreferencesKey("volume_long_press_media")
        private val SCROLL_AUTO_MODE = booleanPreferencesKey("scroll_auto_mode")
        private val RECENTS_LAYOUT_MODE = intPreferencesKey("recents_layout_mode")
        private val HIDE_APP_ICONS = booleanPreferencesKey("hide_app_icons")
        private val THEME_MODE = intPreferencesKey("theme_mode")
        private val TAB_POSITION = intPreferencesKey("tab_position")

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
    val triggerMode: Flow<Int> = context.dataStore.data.map { it[TRIGGER_MODE] ?: TriggerMode.SWIPE_LEFT_EDGE }
    val fontSizeOffset: Flow<Int> = context.dataStore.data.map { it[FONT_SIZE_OFFSET] ?: 0 }
    val einkControlsEnabled: Flow<Boolean> = context.dataStore.data.map { it[EINK_CONTROLS_ENABLED] ?: false }

    val shortcutFrontLight: Flow<Int> = context.dataStore.data.map { it[SHORTCUT_FRONT_LIGHT] ?: ShortcutTrigger.NONE }
    val shortcutEinkRefresh: Flow<Int> = context.dataStore.data.map { it[SHORTCUT_EINK_REFRESH] ?: ShortcutTrigger.NONE }
    val shortcutRefreshCycle: Flow<Int> = context.dataStore.data.map { it[SHORTCUT_REFRESH_CYCLE] ?: ShortcutTrigger.NONE }
    val refreshCycleModeA: Flow<Int> = context.dataStore.data.map { it[REFRESH_CYCLE_MODE_A] ?: 1 } // default Contrast
    val refreshCycleModeB: Flow<Int> = context.dataStore.data.map { it[REFRESH_CYCLE_MODE_B] ?: 2 } // default Speed
    val volumeLongPressMedia: Flow<Boolean> = context.dataStore.data.map { it[VOLUME_LONG_PRESS_MEDIA] ?: false }
    val scrollAutoMode: Flow<Boolean> = context.dataStore.data.map { it[SCROLL_AUTO_MODE] ?: false }
    val recentsLayoutMode: Flow<Int> = context.dataStore.data.map { it[RECENTS_LAYOUT_MODE] ?: RecentsLayoutMode.INLINE }
    val hideAppIcons: Flow<Boolean> = context.dataStore.data.map { it[HIDE_APP_ICONS] ?: false }
    val themeMode: Flow<Int> = context.dataStore.data.map { it[THEME_MODE] ?: ThemeMode.SYSTEM }
    val tabPosition: Flow<Int> = context.dataStore.data.map { it[TAB_POSITION] ?: TabPosition.TOP }

    suspend fun setHeadsUpEnabled(enabled: Boolean) { context.dataStore.edit { it[HEADS_UP_ENABLED] = enabled } }
    suspend fun setRecentAppsEnabled(enabled: Boolean) { context.dataStore.edit { it[RECENT_APPS_ENABLED] = enabled } }
    suspend fun setAutoStart(enabled: Boolean) { context.dataStore.edit { it[AUTO_START] = enabled } }
    suspend fun setTriggerMode(mode: Int) { context.dataStore.edit { it[TRIGGER_MODE] = mode } }
    suspend fun setFontSizeOffset(offset: Int) { context.dataStore.edit { it[FONT_SIZE_OFFSET] = offset.coerceIn(-2, 4) } }
    suspend fun setEinkControlsEnabled(enabled: Boolean) { context.dataStore.edit { it[EINK_CONTROLS_ENABLED] = enabled } }

    suspend fun setRefreshCycleModeA(mode: Int) { context.dataStore.edit { it[REFRESH_CYCLE_MODE_A] = mode } }
    suspend fun setRefreshCycleModeB(mode: Int) { context.dataStore.edit { it[REFRESH_CYCLE_MODE_B] = mode } }
    suspend fun setVolumeLongPressMedia(enabled: Boolean) { context.dataStore.edit { it[VOLUME_LONG_PRESS_MEDIA] = enabled } }
    suspend fun setScrollAutoMode(enabled: Boolean) { context.dataStore.edit { it[SCROLL_AUTO_MODE] = enabled } }
    suspend fun setRecentsLayoutMode(mode: Int) { context.dataStore.edit { it[RECENTS_LAYOUT_MODE] = mode } }
    suspend fun setHideAppIcons(enabled: Boolean) { context.dataStore.edit { it[HIDE_APP_ICONS] = enabled } }
    suspend fun setThemeMode(mode: Int) { context.dataStore.edit { it[THEME_MODE] = mode } }
    suspend fun setTabPosition(pos: Int) { context.dataStore.edit { it[TAB_POSITION] = pos } }

    /**
     * Assign a trigger to an action. Automatically unassigns that trigger from any other action.
     * Pass ShortcutTrigger.NONE to clear the assignment.
     */
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
