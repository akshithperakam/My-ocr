package com.codecapture.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.codecapture.domain.model.HidConnectionState
import com.codecapture.domain.model.HostDeviceProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

interface HidScrollTransport {
    val connectionState: StateFlow<HidConnectionState>
    val isConnected: Boolean
    suspend fun scrollVertical(ticksDown: Int, invertDirection: Boolean = false): Boolean
    suspend fun sendKeyTap(keyCode: Byte, modifierMask: Byte = 0x00): Boolean
}

/**
 * Manages Android's BluetoothHidDevice profile (API 28+) so the Pixel 8a acts as a
 * standard Bluetooth combo keyboard/mouse peripheral with zero host-side software.
 */
class BluetoothHidController(
    private val context: Context
) : HidScrollTransport {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val callbackExecutor = Executors.newSingleThreadExecutor()

    private var hidDeviceProxy: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null
    private var isAppRegistered: Boolean = false

    private val _connectionState = MutableStateFlow<HidConnectionState>(
        if (bluetoothAdapter == null) HidConnectionState.Unavailable else HidConnectionState.Disconnected
    )
    override val connectionState: StateFlow<HidConnectionState> = _connectionState.asStateFlow()

    override val isConnected: Boolean
        get() = connectedHost != null && _connectionState.value is HidConnectionState.Connected

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDeviceProxy = proxy as BluetoothHidDevice
            registerHidApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDeviceProxy = null
            connectedHost = null
            isAppRegistered = false
            _connectionState.value = HidConnectionState.Disconnected
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isAppRegistered = registered
            if (registered) {
                if (connectedHost == null) {
                    _connectionState.value = HidConnectionState.ReadyForConnection
                }
            } else {
                connectedHost = null
                _connectionState.value = HidConnectionState.Disconnected
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    val name = runCatching { device?.name }.getOrNull() ?: "Bluetooth Host"
                    val addr = device?.address ?: ""
                    _connectionState.value = HidConnectionState.Connected(name, addr)
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    val name = runCatching { device?.name }.getOrNull() ?: "Bluetooth Host"
                    val addr = device?.address ?: ""
                    _connectionState.value = HidConnectionState.Connecting(name, addr)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedHost?.address == device?.address) {
                        connectedHost = null
                    }
                    _connectionState.value = if (isAppRegistered) {
                        HidConnectionState.ReadyForConnection
                    } else {
                        HidConnectionState.Disconnected
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startProfile(): Boolean {
        val adapter = bluetoothAdapter ?: run {
            _connectionState.value = HidConnectionState.Unavailable
            return false
        }
        if (!adapter.isEnabled) {
            _connectionState.value = HidConnectionState.Error("Bluetooth is turned off")
            return false
        }
        if (hidDeviceProxy != null && isAppRegistered) {
            return true
        }
        _connectionState.value = HidConnectionState.RegisteringProfile
        return adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp() {
        val proxy = hidDeviceProxy ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "CodeCapture HID",
            "CodeCapture Scroll & Keyboard Controller",
            "CodeCapture",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HidReportDescriptor.COMPOSITE_KEYBOARD_MOUSE_DESCRIPTOR
        )
        val outQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )
        val registered = proxy.registerApp(sdp, null, outQos, callbackExecutor, hidCallback)
        if (!registered) {
            _connectionState.value = HidConnectionState.Error(
                "Failed to register Bluetooth HID profile on this device."
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun listBondedComputers(): List<HostDeviceProfile> {
        val adapter = bluetoothAdapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty().map { device ->
                val name = device.name ?: "Unnamed Computer"
                HostDeviceProfile(
                    id = device.address,
                    bluetoothAddress = device.address,
                    systemName = name,
                    displayName = name
                )
            }
        }.getOrElse { emptyList() }
    }

    @SuppressLint("MissingPermission")
    fun connectToAddress(bluetoothAddress: String): Boolean {
        val adapter = bluetoothAdapter ?: return false
        val proxy = hidDeviceProxy ?: return false
        if (!isAppRegistered) return false
        val device = runCatching { adapter.getRemoteDevice(bluetoothAddress) }.getOrNull() ?: return false
        _connectionState.value = HidConnectionState.Connecting(
            runCatching { device.name }.getOrNull() ?: bluetoothAddress,
            bluetoothAddress
        )
        return proxy.connect(device)
    }

    @SuppressLint("MissingPermission")
    fun disconnect(): Boolean {
        val proxy = hidDeviceProxy ?: return false
        val host = connectedHost ?: return false
        return proxy.disconnect(host)
    }

    @SuppressLint("MissingPermission")
    fun shutdown() {
        runCatching {
            connectedHost?.let { hidDeviceProxy?.disconnect(it) }
            if (isAppRegistered) {
                hidDeviceProxy?.unregisterApp()
            }
            hidDeviceProxy?.let {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it)
            }
        }.onFailure { Log.w(TAG, "Error shutting down HID profile", it) }
        hidDeviceProxy = null
        connectedHost = null
        isAppRegistered = false
        _connectionState.value = HidConnectionState.Disconnected
    }

    /**
     * Sends incremental wheel ticks to prevent host OS scroll acceleration spikes.
     * Positive ticksDown scrolls DOWN; negative ticksDown scrolls UP.
     */
    @SuppressLint("MissingPermission")
    override suspend fun scrollVertical(ticksDown: Int, invertDirection: Boolean): Boolean {
        val proxy = hidDeviceProxy ?: return false
        val host = connectedHost ?: return false
        if (ticksDown == 0) return true

        // Standard HID mouse wheel: negative delta scrolls down on Windows/Linux/macOS default
        val signedTotal = if (invertDirection) ticksDown else -ticksDown
        val stepDirection = if (signedTotal > 0) 1 else -1
        var remaining = kotlin.math.abs(signedTotal)

        while (remaining > 0) {
            // Send small chunks (<= 3 ticks per HID packet) for predictable editor displacement
            val chunk = minOf(remaining, 3) * stepDirection
            val report = HidReportDescriptor.buildMouseWheelReport(chunk)
            val sent = proxy.sendReport(host, HidReportDescriptor.REPORT_ID_MOUSE, report)
            if (!sent) return false
            remaining -= kotlin.math.abs(chunk)
            if (remaining > 0) {
                delay(28L)
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    override suspend fun sendKeyTap(keyCode: Byte, modifierMask: Byte): Boolean {
        val proxy = hidDeviceProxy ?: return false
        val host = connectedHost ?: return false
        val press = HidReportDescriptor.buildKeyboardReport(modifierMask, keyCode)
        val release = HidReportDescriptor.buildKeyboardReport()
        val okPress = proxy.sendReport(host, HidReportDescriptor.REPORT_ID_KEYBOARD, press)
        if (!okPress) return false
        delay(35L)
        return proxy.sendReport(host, HidReportDescriptor.REPORT_ID_KEYBOARD, release)
    }

    fun hasRequiredBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        private const val TAG = "BluetoothHidController"
    }
}
