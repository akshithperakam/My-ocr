package com.codecapture.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.codecapture.di.AppContainer
import com.codecapture.domain.model.CaptureConfig
import com.codecapture.domain.model.CodeDensityPreset
import com.codecapture.domain.model.HidConnectionState
import com.codecapture.domain.model.HostDeviceProfile
import com.codecapture.domain.model.NormalizedRect
import com.codecapture.domain.model.OperatingMode
import com.codecapture.vision.CaptureHudState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CaptureViewModel(
    private val container: AppContainer
) : ViewModel() {

    val hudState: StateFlow<CaptureHudState> = container.captureOrchestrator.hudState
    val hidState: StateFlow<HidConnectionState> = container.bluetoothHidController.connectionState
    val savedDevices: StateFlow<List<HostDeviceProfile>> =
        container.sessionRepository.observeDeviceProfiles()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedDevice = MutableStateFlow<HostDeviceProfile?>(null)
    val selectedDevice: StateFlow<HostDeviceProfile?> = _selectedDevice.asStateFlow()

    private val _config = MutableStateFlow(CaptureConfig())
    val config: StateFlow<CaptureConfig> = _config.asStateFlow()

    private val _sessionName = MutableStateFlow("CodeSession")
    val sessionName: StateFlow<String> = _sessionName.asStateFlow()

    private val _manualScrollTicks = MutableStateFlow(3)
    val manualScrollTicks: StateFlow<Int> = _manualScrollTicks.asStateFlow()

    fun updateSessionName(name: String) {
        _sessionName.value = name
    }

    fun selectDevice(profile: HostDeviceProfile) {
        _selectedDevice.value = profile
    }

    fun connectSelectedDevice() {
        val profile = _selectedDevice.value ?: return
        container.bluetoothHidController.startProfile()
        container.bluetoothHidController.connectToAddress(profile.bluetoothAddress)
        viewModelScope.launch {
            container.sessionRepository.upsertDeviceProfile(
                profile.copy(lastConnectedEpochMs = System.currentTimeMillis())
            )
        }
    }

    fun disconnectHid() {
        container.bluetoothHidController.disconnect()
    }

    fun setOperatingMode(mode: OperatingMode) {
        _config.update {
            it.copy(
                operatingMode = mode,
                targetOverlapRatio = if (mode == OperatingMode.HANDHELD) maxOf(it.targetOverlapRatio, 0.28f) else it.targetOverlapRatio
            )
        }
        container.captureOrchestrator.setOperatingMode(mode)
    }

    fun setDensityPreset(preset: CodeDensityPreset) {
        _config.update {
            it.copy(densityPreset = preset, targetOverlapRatio = preset.defaultTargetOverlap)
        }
    }

    fun setTargetOverlap(ratio: Float) {
        _config.update { it.copy(targetOverlapRatio = ratio.coerceIn(0.12f, 0.45f)) }
    }

    fun setManualScrollIncrement(ticks: Int) {
        _manualScrollTicks.value = ticks.coerceIn(1, 20)
    }

    fun setManualRegion(rect: NormalizedRect?) {
        container.captureOrchestrator.setManualRegion(rect)
    }

    fun calibrate() {
        viewModelScope.launch {
            container.captureOrchestrator.calibrate(_config.value, _selectedDevice.value)
        }
    }

    fun manualCapture() {
        viewModelScope.launch {
            container.captureOrchestrator.captureSingleManualFrame(
                sessionName = _sessionName.value,
                config = _config.value,
                deviceProfile = _selectedDevice.value
            )
        }
    }

    fun manualScrollDown() {
        val invert = _selectedDevice.value?.scrollCalibration?.invertScrollDirection ?: false
        viewModelScope.launch {
            container.bluetoothHidController.scrollVertical(_manualScrollTicks.value, invert)
        }
    }

    fun manualScrollUp() {
        val invert = _selectedDevice.value?.scrollCalibration?.invertScrollDirection ?: false
        viewModelScope.launch {
            container.bluetoothHidController.scrollVertical(-_manualScrollTicks.value, invert)
        }
    }

    fun startAutoCapture() {
        container.captureOrchestrator.startAutomaticCapture(
            scope = viewModelScope,
            sessionName = _sessionName.value,
            config = _config.value,
            deviceProfile = _selectedDevice.value
        )
    }

    fun pauseAutoCapture() = container.captureOrchestrator.pauseAutomaticCapture()
    fun resumeAutoCapture() = container.captureOrchestrator.resumeAutomaticCapture()
    fun overrideEndOfContent() = container.captureOrchestrator.overrideEndOfContent()

    fun stopAndFinishSession() {
        viewModelScope.launch {
            container.captureOrchestrator.stopAndFinishSession(_config.value.autoExtractOnComplete)
        }
    }

    fun refreshBondedDevices() {
        val bonded = container.bluetoothHidController.listBondedComputers()
        viewModelScope.launch {
            val existingById = savedDevices.value.associateBy { it.id }
            bonded.forEach { b ->
                if (!existingById.containsKey(b.id)) {
                    container.sessionRepository.upsertDeviceProfile(b)
                }
            }
        }
    }

    fun renameDevice(profile: HostDeviceProfile, newDisplayName: String) {
        viewModelScope.launch {
            container.sessionRepository.upsertDeviceProfile(profile.copy(displayName = newDisplayName.trim()))
        }
    }

    fun toggleInvertScroll(profile: HostDeviceProfile) {
        viewModelScope.launch {
            val updated = profile.copy(
                scrollCalibration = profile.scrollCalibration.copy(
                    invertScrollDirection = !profile.scrollCalibration.invertScrollDirection
                )
            )
            container.sessionRepository.upsertDeviceProfile(updated)
            if (_selectedDevice.value?.id == updated.id) {
                _selectedDevice.value = updated
            }
        }
    }

    fun removeDevice(profileId: String) {
        viewModelScope.launch {
            container.sessionRepository.deleteDeviceProfile(profileId)
            if (_selectedDevice.value?.id == profileId) {
                _selectedDevice.value = null
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CaptureViewModel(container) as T
            }
    }
}
