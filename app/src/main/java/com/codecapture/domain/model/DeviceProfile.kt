package com.codecapture.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ScrollCalibration(
    val pixelsPerWheelTick: Float = 96f,
    val linesPerWheelTick: Float = 3.0f,
    val invertScrollDirection: Boolean = false,
    val settleDelayMs: Long = 420L,
    val samplesCollected: Int = 0
)

@Serializable
data class HostDeviceProfile(
    val id: String,
    val bluetoothAddress: String,
    val systemName: String,
    val displayName: String,
    val operatingSystemHint: String = "Unknown",
    val scrollCalibration: ScrollCalibration = ScrollCalibration(),
    val lastConnectedEpochMs: Long = 0L
)

sealed interface HidConnectionState {
    data object Unavailable : HidConnectionState
    data object Disconnected : HidConnectionState
    data object RegisteringProfile : HidConnectionState
    data object ReadyForConnection : HidConnectionState
    data class Connecting(val deviceName: String, val address: String) : HidConnectionState
    data class Connected(val deviceName: String, val address: String) : HidConnectionState
    data class Error(val message: String) : HidConnectionState
}
