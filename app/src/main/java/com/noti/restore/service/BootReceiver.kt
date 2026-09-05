package com.noti.restore.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.noti.restore.overlay.OverlayService
import com.noti.restore.ui.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        // Reading preferences is async, and onReceive returns immediately — goAsync() keeps the
        // receiver alive until the decision has actually been made.
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val prefs = PreferencesManager.getInstance(appContext)
                val enabled = prefs.serviceEnabled.first()
                val autoStart = prefs.autoStart.first()
                if (enabled && autoStart) {
                    appContext.startForegroundService(Intent(appContext, OverlayService::class.java))
                    Log.d("KompaktX", "Boot completed, overlay service started")
                } else {
                    Log.d("KompaktX", "Boot completed, not starting: " +
                        "enabled=$enabled autoStart=$autoStart")
                }
            } catch (e: Exception) {
                Log.e("KompaktX", "Failed to start overlay service on boot", e)
            } finally {
                pending.finish()
            }
        }
    }
}
