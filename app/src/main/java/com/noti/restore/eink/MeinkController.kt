package com.noti.restore.eink

import android.os.IBinder
import android.util.Log

private const val TAG = "MeinkController"
private const val MEINK_SERVICE_NAME = "meink"

object EinkMode {
    const val CONTRAST = 1
    const val SPEED = 2
    const val CLEAR = 3
    const val LIGHT = 4

    fun label(mode: Int): String = when (mode) {
        CONTRAST -> "Contrast"
        SPEED -> "Speed"
        CLEAR -> "Clear"
        LIGHT -> "Light"
        else -> "?"
    }

    fun code(mode: Int): String = when (mode) {
        CONTRAST -> "GC16"
        SPEED -> "A2"
        CLEAR -> "INIT"
        LIGHT -> "DU"
        else -> "?"
    }

    val ALL = intArrayOf(CONTRAST, SPEED, CLEAR, LIGHT)
}

object MeinkController {

    private var meinkService: IMeinkService? = null
    var isAvailable = false
        private set
    var currentMode: Int = EinkMode.SPEED
        private set

    fun init() {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, MEINK_SERVICE_NAME) as? IBinder
            if (binder != null) {
                meinkService = IMeinkService.Stub.asInterface(binder)
                isAvailable = true
                Log.d(TAG, "Meink service connected")
            } else {
                Log.d(TAG, "Meink service not found on this device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Meink service", e)
            isAvailable = false
        }
    }

    fun setMode(mode: Int, packageName: String = "com.kompaktx.app") {
        try {
            meinkService?.setDisplayMode(packageName, mode)
            currentMode = mode
            Log.d(TAG, "Set e-ink mode to ${EinkMode.label(mode)} ($mode)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set e-ink mode", e)
        }
    }
}
