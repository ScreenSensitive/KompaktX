package com.noti.restore.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.IBinder
import com.noti.restore.eink.MeinkController
import com.noti.restore.service.KompaktXListener
import com.noti.restore.service.RecentsButtonService
import com.noti.restore.ui.MainActivity
import com.noti.restore.ui.PreferencesManager
import com.noti.restore.ui.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "kompaktx_overlay"
        private const val NOTIFICATION_ID = 1001
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> OverlayPanelManager.reapplyBrightness()
                // Arm the wake-restore guard as the screen goes OFF, not when it comes back on.
                // The OS rewrites SCREEN_BRIGHTNESS while turning the display on, and that write
                // reaches our ContentObserver before the SCREEN_ON broadcast reaches us — so
                // anything armed on SCREEN_ON is already too late. See onScreenOff().
                Intent.ACTION_SCREEN_OFF -> OverlayPanelManager.onScreenOff()
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        MeinkController.init()
        observePreferences()
        OverlayPanelManager.createTouchStrip(this)
        registerReceiver(screenStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    /**
     * Keep the overlay's settings in sync from the service itself.
     *
     * These used to be pushed into OverlayPanelManager only by MainActivity's LaunchedEffects,
     * which meant that any time the service ran without the app having been opened — after a
     * reboot via BootReceiver, or a START_STICKY restart following a process kill — the overlay
     * came up on hardcoded defaults instead of the user's settings: swipe-from-left trigger, light
     * theme, tabs at the top, and an empty hidden-apps list, so filtered apps reappeared.
     *
     * Collecting here rather than reading once also means changes apply while the app is closed.
     */
    private fun observePreferences() {
        val prefs = PreferencesManager.getInstance(this)

        fun <T> watch(flow: Flow<T>, apply: (T) -> Unit) {
            serviceScope.launch { flow.collect { apply(it) } }
        }

        watch(prefs.triggerMode) {
            if (OverlayPanelManager.triggerMode != it) {
                OverlayPanelManager.triggerMode = it
                OverlayPanelManager.recreateTouchStrip()
            }
        }
        watch(prefs.themeMode) { mode ->
            val systemDark = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            OverlayPanelManager.darkMode = when (mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> systemDark
            }
        }
        watch(prefs.headsUpEnabled) { OverlayPanelManager.headsUpEnabled = it }
        watch(prefs.recentAppsEnabled) { OverlayPanelManager.recentAppsEnabled = it }
        watch(prefs.recentsLayoutMode) { OverlayPanelManager.recentsLayoutMode = it }
        watch(prefs.tabPosition) { OverlayPanelManager.tabPosition = it }
        watch(prefs.clockStyle) { OverlayPanelManager.clockStyle = it }
        watch(prefs.hideAppIcons) { OverlayPanelManager.hideAppIcons = it }
        watch(prefs.fontSizeOffset) { OverlayPanelManager.fontSizeOffset = it }
        watch(prefs.einkControlsEnabled) { OverlayPanelManager.einkControlsEnabled = it }
        watch(prefs.hapticsEnabled) { OverlayPanelManager.hapticsEnabled = it }
        watch(prefs.headsUpDuration) { OverlayPanelManager.headsUpDurationMs = it.toLong() }
        watch(prefs.replyOnEnter) { OverlayPanelManager.replyOnEnter = it }
        watch(prefs.showLocationToggle) { OverlayPanelManager.showLocationToggle = it }
        watch(prefs.hiddenRecentApps) { OverlayPanelManager.hiddenRecentApps = it }
        watch(prefs.hiddenNotiApps) {
            OverlayPanelManager.hiddenNotiApps = it
            KompaktXListener.setHiddenNotiApps(it)
        }
        watch(prefs.volumeLongPressMedia) { RecentsButtonService.volumeLongPressMediaEnabled = it }
        watch(prefs.popupReplyTrigger) { RecentsButtonService.popupReplyTrigger = it }

        // The three shortcut assignments have to be applied together: updateShortcuts() clears
        // every trigger before reassigning, so pushing them one at a time would leave the others
        // unassigned until their own flow happened to emit.
        serviceScope.launch {
            combine(
                prefs.shortcutFrontLight,
                prefs.shortcutEinkRefresh,
                prefs.shortcutRefreshCycle
            ) { frontLight, einkRefresh, refreshCycle ->
                Triple(frontLight, einkRefresh, refreshCycle)
            }.collect { (frontLight, einkRefresh, refreshCycle) ->
                RecentsButtonService.updateShortcuts(frontLight, einkRefresh, refreshCycle)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
        OverlayPanelManager.removeTouchStrip()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "KompaktX Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the notification panel overlay active"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("KompaktX")
            .setContentText("Notification panel overlay is active")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }
}
