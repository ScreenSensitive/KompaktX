package com.noti.restore.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.noti.restore.overlay.OverlayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("KompaktX", "Boot completed, starting overlay service")
            try {
                val serviceIntent = Intent(context, OverlayService::class.java)
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e("KompaktX", "Failed to start overlay service on boot", e)
            }
        }
    }
}
