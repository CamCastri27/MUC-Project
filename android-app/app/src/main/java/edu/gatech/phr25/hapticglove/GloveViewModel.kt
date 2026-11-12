package edu.gatech.phr25.hapticglove

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * APP LOGIC — holds the state of all five motors and decides what to send to the glove.
 * MainActivity.kt calls the functions below when buttons are pressed; this file updates the state
 * and sends the changes over Bluetooth (BleController.kt), or just logs them in Demo mode.
 *
 * To add a new action (e.g. a "pattern" button), add a function here that updates `_ui` and then
 * calls `wake.trySend(Unit)` to send the changes, and call it from a button in MainActivity.kt.
 */

/** Everything the screen needs to draw itself. */
data class GloveUiState(
    /** One entry per motor, indexed by Finger.ordinal (THUMB = 0 ... PINKY = 4). */
    val motors: List<MotorState> = List(Finger.entries.size) { MotorState() },
    /** Finger whose controls are shown, or null. */
    val selected: Finger? = null,
    /** true = don't use Bluetooth, just log what would be sent. */
    val demoMode: Boolean = false,
    /** Most recent packets "sent" — shown in Demo mode so you can verify what the ESP32 would receive. */
    val log: List<String> = emptyList(),
)

class GloveViewModel(app: Application) : AndroidViewModel(app) {
    /** Bluetooth connection to the glove. */
    val ble = BleController(app)

    // Current screen state. Update it with `_ui.update { ... }`; the screen redraws automatically.
    private val _ui = MutableStateFlow(GloveUiState())
    val ui: StateFlow<GloveUiState> = _ui.asStateFlow()

    // Slider drags fire many updates per second; BLE can only take one write at a time.
    // We keep only the latest desired state per motor and send whatever has changed.
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lastSent = arrayOfNulls<MotorState>(Finger.entries.size)

    init {
        // Background loop: every time something changes, send the differences to the glove.
        viewModelScope.launch {
            for (unused in wake) flush()
        }
        viewModelScope.launch {
            // On (re)connect, push the full current state so the glove matches the screen.
            ble.state.collect { if (it is ConnectionState.Connected) { lastSent.fill(null); wake.trySend(Unit) } }
        }
    }

    // ---- Actions called from the screen ----

    /** Tap on the hand: show controls for [finger], or hide them when null. */
    fun select(finger: Finger?) = _ui.update { it.copy(selected = finger) }

    /** On/off switch for one finger. */
    fun setOn(finger: Finger, on: Boolean) = updateMotor(finger) { it.copy(on = on) }

    /** Intensity slider for one finger (0-100). */
    fun setIntensity(finger: Finger, intensity: Int) = updateMotor(finger) { it.copy(intensity = intensity) }

    /** Turns every motor on at the same intensity (the "All On" button passes ALL_ON_INTENSITY). */
    fun allOn(intensity: Int) {
        _ui.update { s -> s.copy(motors = s.motors.map { MotorState(on = true, intensity = intensity) }) }
        wake.trySend(Unit)
    }

    /** Turns every motor off. Each motor keeps its intensity for the next time it's turned on. */
    fun allOff() {
        _ui.update { s -> s.copy(motors = s.motors.map { it.copy(on = false) }) }
        wake.trySend(Unit)
    }

    /** Demo switch. Turning it on disconnects from the glove. */
    fun setDemoMode(enabled: Boolean) {
        if (enabled) ble.disconnect()
        lastSent.fill(null)
        _ui.update { it.copy(demoMode = enabled, log = emptyList()) }
        wake.trySend(Unit)
    }

    // ---- Internals ----

    /** Applies [f] to one motor's state, then schedules a send. */
    private fun updateMotor(finger: Finger, f: (MotorState) -> MotorState) {
        _ui.update { s ->
            s.copy(motors = s.motors.toMutableList().also { it[finger.ordinal] = f(it[finger.ordinal]) })
        }
        wake.trySend(Unit)
    }

    /** Sends one packet for every motor whose state differs from what the glove last received. */
    private suspend fun flush() {
        val s = _ui.value
        val connected = ble.state.value is ConnectionState.Connected
        if (!s.demoMode && !connected) return
        for (finger in Finger.entries) {
            val desired = s.motors[finger.ordinal]
            if (desired == lastSent[finger.ordinal]) continue
            val ok = if (s.demoMode) true else ble.writeMotor(finger.ordinal, desired.on, desired.intensity)
            if (ok) lastSent[finger.ordinal] = desired
            log(finger, desired, ok)
        }
        // If the user kept dragging while we were writing, go again.
        if (_ui.value.motors != s.motors) wake.trySend(Unit)
    }

    /** Adds a line to the Demo log, e.g. "Index  pin 5  ON   40%  [01 01 28]". Keeps the newest 30. */
    private fun log(finger: Finger, m: MotorState, ok: Boolean) {
        val bytes = GloveProtocol.encode(finger.ordinal, m.on, m.intensity)
            .joinToString(" ") { "%02X".format(it) }
        val line = "${finger.label.padEnd(6)} pin ${finger.pin}  ${if (m.on) "ON " else "OFF"} ${m.intensity.toString().padStart(3)}%  [$bytes]${if (ok) "" else "  FAILED"}"
        _ui.update { it.copy(log = (listOf(line) + it.log).take(30)) }
    }

    /** App closing: drop the Bluetooth connection (the firmware then turns all motors off). */
    override fun onCleared() {
        ble.disconnect()
    }
}
