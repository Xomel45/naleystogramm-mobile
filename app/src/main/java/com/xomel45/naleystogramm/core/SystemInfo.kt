package com.xomel45.naleystogramm.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.json.JSONObject

// Mirrors: src/core/systeminfo.h — collects static device/OS info once at startup.
// Result is included in HANDSHAKE and shown in ContactProfileDialog.

object SystemInfo {
    private var deviceType = "Android"
    private var cpuModel   = ""
    private var ramAmount  = ""
    private var osName     = ""
    private var collected  = false

    fun collect(context: Context) {
        if (collected) return
        collected  = true
        deviceType = "Android"
        cpuModel   = Build.HARDWARE
        osName     = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        ramAmount = "${mi.totalMem / (1024 * 1024)} MB"
    }

    fun deviceType() = deviceType
    fun cpuModel()   = cpuModel
    fun ramAmount()  = ramAmount
    fun osName()     = osName

    fun toJson(): JSONObject = JSONObject().apply {
        put("device_type", deviceType)
        put("cpu_model",   cpuModel)
        put("ram",         ramAmount)
        put("os",          osName)
        put("model",       "${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun toJsonForHandshake(externalIp: String): JSONObject = toJson().apply {
        put("external_ip", externalIp)
    }
}
