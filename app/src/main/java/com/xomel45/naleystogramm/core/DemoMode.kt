package com.xomel45.naleystogramm.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Mirrors: src/core/demomode.h — visual masking for screenshots/demos.
// Network layer is NOT affected: real UUID/name/IP go to peers unchanged.

object DemoMode {
    private const val DEMO_NAME = "User-0000"
    private const val DEMO_UUID = "00000000-0000-0000-0000-000000000000"
    private const val DEMO_IP   = "0.0.0.0"
    private const val DEMO_PORT = 0

    private val _enabled = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabled

    var enabled: Boolean
        get() = _enabled.value
        set(value) { _enabled.value = value }

    fun displayName(real: String): String = if (enabled) DEMO_NAME else real
    fun uuid(real: String): String        = if (enabled) DEMO_UUID else real
    fun ip(real: String): String          = if (enabled) DEMO_IP   else real
    fun port(real: Int): Int              = if (enabled) DEMO_PORT  else real
}
