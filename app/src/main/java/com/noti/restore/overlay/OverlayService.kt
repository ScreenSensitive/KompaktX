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
import android.os.IBinder
import com.noti.restore.eink.MeinkController
import com.noti.restore.ui.MainActivity

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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        MeinkController.init()
        OverlayPanelManager.createTouchStrip(this)
        registerReceiver(screenStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
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
