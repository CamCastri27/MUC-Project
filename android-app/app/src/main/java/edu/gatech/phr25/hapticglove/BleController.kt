package edu.gatech.phr25.hapticglove

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * BLUETOOTH — finds the glove, connects to it, and sends motor packets.
 * Usually nothing here needs editing. The glove's name and IDs are in GloveProtocol.kt, and
 * the timeouts are at the bottom of this file.
 *
 * Steps: connect() scans for the glove -> connectTo() opens a connection ->
 * onServicesDiscovered() finds the motor characteristic -> Connected -> writeMotor() sends packets.
 */

/** Connection status shown in the ConnectionBar on the main screen. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Scanning : ConnectionState
    data class Connecting(val name: String) : ConnectionState
    data class Connected(val name: String) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/**
 * Minimal BLE central: scans for the glove, connects, and writes motor packets.
 * Permissions must already be granted before calling [connect].
 */
@SuppressLint("MissingPermission")
class BleController(context: Context) {
    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    private var gatt: BluetoothGatt? = null
    private var motorChar: BluetoothGattCharacteristic? = null
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var scanning = false

    val isBluetoothAvailable: Boolean get() = adapter != null
    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    /** Starts scanning for the glove; connects to the first match. Gives up after SCAN_TIMEOUT_MS. */
    fun connect() {
        val a = adapter ?: return fail("This device has no Bluetooth (use Demo mode on the emulator)")
        if (!a.isEnabled) return fail("Bluetooth is off")
        val scanner = a.bluetoothLeScanner ?: return fail("BLE scanner unavailable")
        disconnect()
        _state.value = ConnectionState.Scanning
        // Filter by name OR service UUID — some ESP32 builds put the UUID in the scan response only.
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(GloveProtocol.DEVICE_NAME).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(GloveProtocol.SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, scanCallback)
        scanning = true
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    /** Stops scanning and/or closes the connection. Safe to call at any time. */
    fun disconnect() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        motorChar = null
        pendingWrite?.complete(false)
        _state.value = ConnectionState.Disconnected
    }

    /** Writes one motor packet and suspends until the ESP32 acknowledges it (or timeout). */
    suspend fun writeMotor(index: Int, on: Boolean, intensity: Int): Boolean {
        val g = gatt ?: return false
        val c = motorChar ?: return false
        val payload = GloveProtocol.encode(index, on, intensity)
        val done = CompletableDeferred<Boolean>()
        pendingWrite = done
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!started) return false
        return withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() } ?: false
    }

    /** Fires SCAN_TIMEOUT_MS after a scan starts if no glove was found. */
    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            fail("Glove not found. Is the ESP32 powered and advertising \"${GloveProtocol.DEVICE_NAME}\"?")
        }
    }

    private fun stopScan() {
        mainHandler.removeCallbacks(scanTimeout)
        if (scanning) {
            scanning = false
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        }
    }

    private fun fail(msg: String) {
        _state.value = ConnectionState.Error(msg)
    }

    /** Called by Android when a matching device is found while scanning. */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            connectTo(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            fail("BLE scan failed (code $errorCode)")
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        val name = device.name ?: GloveProtocol.DEVICE_NAME
        _state.value = ConnectionState.Connecting(name)
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Called by Android as the connection progresses and when each write is acknowledged. */
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    g.close()
                    if (gatt == g) {
                        gatt = null
                        motorChar = null
                        pendingWrite?.complete(false)
                        _state.value = if (status == BluetoothGatt.GATT_SUCCESS) {
                            ConnectionState.Disconnected
                        } else {
                            ConnectionState.Error("Connection lost (status $status)")
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(GloveProtocol.SERVICE_UUID)?.getCharacteristic(GloveProtocol.MOTOR_CHAR_UUID)
            if (c == null) {
                fail("Connected, but the glove's motor service was not found. Flash the latest firmware.")
                g.disconnect()
                return
            }
            motorChar = c
            _state.value = ConnectionState.Connected(g.device.name ?: GloveProtocol.DEVICE_NAME)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    companion object {
        // EDIT: how long to search for the glove before showing "Glove not found" (milliseconds).
        private const val SCAN_TIMEOUT_MS = 10_000L
        // EDIT: how long to wait for the glove to confirm each packet before marking it FAILED.
        private const val WRITE_TIMEOUT_MS = 1_000L
    }
}
