package edu.gatech.phr25.hapticglove

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
 * BLUETOOTH — finds the glove, connects to it, sends motor commands and listens for its replies.
 * Usually nothing here needs editing. The glove's name and IDs are in GloveProtocol.kt, and
 * the timeouts are at the bottom of this file.
 *
 * Steps: connect() scans for the glove -> connectTo() opens a connection ->
 * onServicesDiscovered() finds the command characteristic and queues the setup steps (turn on
 * reply + battery notifications, read the battery) -> Connected -> writeMotor() sends text commands.
 * Replies ("START_OK", ...) appear in [lastReply]; battery level appears in [battery].
 */

/** Connection status shown in the ConnectionBar on the main screen. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Scanning : ConnectionState
    data class Connecting(val name: String) : ConnectionState
    data class Connected(val name: String) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/** Glove battery, from the battery characteristic: 2 bytes = [level 0-100, charging 0|1]. */
data class BatteryInfo(val level: Int, val charging: Boolean) {
    companion object {
        fun parse(bytes: ByteArray?): BatteryInfo? {
            if (bytes == null || bytes.isEmpty()) return null
            return BatteryInfo(
                level = (bytes[0].toInt() and 0xFF).coerceIn(0, 100),
                charging = bytes.size > 1 && bytes[1].toInt() != 0,
            )
        }
    }
}

/**
 * Minimal BLE central: scans for the glove, connects, and writes motor commands.
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
    private var commandChar: BluetoothGattCharacteristic? = null

    // Latest text the glove sent back on the confirm characteristic (e.g. "START_OK"), or null.
    private val _lastReply = MutableStateFlow<String?>(null)
    val lastReply: StateFlow<String?> = _lastReply

    // Latest glove battery reading, or null if not connected / the glove doesn't report it.
    private val _battery = MutableStateFlow<BatteryInfo?>(null)
    val battery: StateFlow<BatteryInfo?> = _battery

    // Setup work to do after connecting, one BLE operation at a time. Each step returns true if it
    // started an operation (we then wait for its callback), false to skip straight to the next one.
    private val setupSteps = ArrayDeque<(BluetoothGatt) -> Boolean>()
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
        commandChar = null
        pendingWrite?.complete(false)
        _lastReply.value = null
        _battery.value = null
        setupSteps.clear()
        _state.value = ConnectionState.Disconnected
    }

    /** Writes one motor command and suspends until the ESP32 acknowledges it (or timeout). */
    suspend fun writeMotor(index: Int, on: Boolean): Boolean {
        val g = gatt ?: return false
        val c = commandChar ?: return false
        val payload = GloveProtocol.encode(index, on)
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
                        commandChar = null
                        pendingWrite?.complete(false)
                        _battery.value = null
                        setupSteps.clear()
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
            val service = g.getService(GloveProtocol.SERVICE_UUID)
            val c = service?.getCharacteristic(GloveProtocol.COMMAND_UUID)
            if (c == null) {
                fail("Connected, but the glove's command service was not found. Flash the latest firmware.")
                g.disconnect()
                return
            }
            commandChar = c
            // BLE can only do one operation at a time, so these run in order and we report
            // Connected once they're all done. Missing characteristics are skipped.
            val confirm = service.getCharacteristic(GloveProtocol.CONFIRM_UUID)
            val battery = service.getCharacteristic(GloveProtocol.BATTERY_UUID)
            setupSteps.clear()
            setupSteps.add { confirm != null && enableNotifications(it, confirm) }
            setupSteps.add { battery != null && enableNotifications(it, battery) }
            setupSteps.add { battery != null && it.readCharacteristic(battery) }
            runNextSetupStep(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            runNextSetupStep(g)
        }

        // Battery read finished, Android 13+.
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (c.uuid == GloveProtocol.BATTERY_UUID && status == BluetoothGatt.GATT_SUCCESS) _battery.value = BatteryInfo.parse(value)
            runNextSetupStep(g)
        }

        // Battery read finished, Android 12 and older.
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (c.uuid == GloveProtocol.BATTERY_UUID && status == BluetoothGatt.GATT_SUCCESS) _battery.value = BatteryInfo.parse(c.value)
            runNextSetupStep(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Glove reply or battery update, Android 13+.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotification(c, value)
        }

        // Glove reply or battery update, Android 12 and older.
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onNotification(c, c.value)
        }
    }

    private fun onNotification(c: BluetoothGattCharacteristic, value: ByteArray?) {
        when (c.uuid) {
            GloveProtocol.CONFIRM_UUID -> _lastReply.value = value?.toString(Charsets.UTF_8)
            GloveProtocol.BATTERY_UUID -> BatteryInfo.parse(value)?.let { _battery.value = it }
        }
    }

    /** Starts the next setup step that applies; reports Connected when none are left. */
    private fun runNextSetupStep(g: BluetoothGatt) {
        if (gatt != g) return
        while (setupSteps.isNotEmpty()) {
            if (setupSteps.removeFirst()(g)) return // wait for its callback
        }
        markConnected(g)
    }

    private fun markConnected(g: BluetoothGatt) {
        if (gatt != g) return
        _state.value = ConnectionState.Connected(g.device.name ?: GloveProtocol.DEVICE_NAME)
    }

    /** Subscribes to a notify characteristic. Returns false if the request couldn't be started. */
    private fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(c, true)) return false
        val d = c.getDescriptor(GloveProtocol.CLIENT_CONFIG_UUID) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    companion object {
        // EDIT: how long to search for the glove before showing "Glove not found" (milliseconds).
        private const val SCAN_TIMEOUT_MS = 10_000L
        // EDIT: how long to wait for the glove to confirm each command before marking it FAILED.
        private const val WRITE_TIMEOUT_MS = 1_000L
    }
}
