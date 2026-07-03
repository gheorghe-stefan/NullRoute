package com.nullroute.vpn

import kotlinx.coroutines.flow.MutableStateFlow

object VpnStateTracker {
    // Pure Kotlin state flow tracker to decouple Android Service dependency from UI/Tests
    val isRunning = MutableStateFlow(false)
}
