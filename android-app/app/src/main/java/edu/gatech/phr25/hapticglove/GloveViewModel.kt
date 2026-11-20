package edu.gatech.phr25.hapticglove

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/*
 * APP LOGIC — holds the state of all five motors and decides what to send to the glove.
 * MainActivity.kt calls the functions below when buttons are pressed; this file updates the state
 * and sends the changes over Bluetooth (BleController.kt), or just logs them in Demo mode.
 *
 * To add a new action (e.g. a "pattern" button), add a function here that updates `_ui` and then
 * calls `wake.trySend(Unit)` to send the changes, and call it from a button in MainActivity.kt.
 */

// ---- Random patterns ----
// EDIT: how long one random-pattern session runs before stopping by itself (4 hours).
private const val PATTERN_SESSION_MS = 4 * 60 * 60 * 1000L
// EDIT: how many motors each pattern turns on (picked at random in this range, inclusive).
private const val PATTERN_MIN_MOTORS = 1
private const val PATTERN_MAX_MOTORS = 5
// EDIT: how long each pattern stays on (picked at random in this range, inclusive), in milliseconds.
private const val PATTERN_MIN_MS = 5_000L
private const val PATTERN_MAX_MS = 10_000L
// EDIT: pause with all motors off between patterns (0 = next pattern starts immediately).
private const val PATTERN_GAP_MS = 0L

/**
 * A running random-pattern session. Times are SystemClock.elapsedRealtime() values.
 * @param number   1 for the first pattern, 2 for the second, ...
 * @param fingers  motors that are on in the current pattern
 */
data class PatternSession(
    val endsAt: Long,
    val number: Int = 0,
    val fingers: List<Finger> = emptyList(),
    val stepEndsAt: Long = 0L,
)

/** Everything the screen needs to draw itself. */
data class GloveUiState(
    /** One entry per motor, indexed by Finger.ordinal (THUMB = 0 ... PINKY = 4). */
    val motors: List<MotorState> = List(Finger.entries.size) { MotorState() },
    /** Finger whose controls are shown, or null. */
    val selected: Finger? = null,
    /** true = don't use Bluetooth, just log what would be sent. */
    val demoMode: Boolean = false,
    /** Most recent commands "sent" — shown in Demo mode so you can verify what the ESP32 would receive. */
    val log: List<String> = emptyList(),
    /** Random-pattern session in progress, or null. Manual controls are locked while it runs. */
    val pattern: PatternSession? = null,
)

class GloveViewModel(app: Application) : AndroidViewModel(app) {
    /** Bluetooth connection to the glove. */
    val ble = BleController(app)

    // Current screen state. Update it with `_ui.update { ... }`; the screen redraws automatically.
    private val _ui = MutableStateFlow(GloveUiState())
    val ui: StateFlow<GloveUiState> = _ui.asStateFlow()

    // Taps can come faster than BLE writes (one at a time).
    // We keep only the latest desired state per motor and send whatever has changed.
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lastSent = arrayOfNulls<MotorState>(Finger.entries.size)

    // The coroutine running the random patterns, or null.
    private var patternJob: Job? = null

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

    /** Turns every motor on (at the fixed GloveProtocol.MOTOR_INTENSITY). */
    fun allOn() {
        _ui.update { s -> s.copy(motors = s.motors.map { MotorState(on = true) }) }
        wake.trySend(Unit)
    }

    /** Turns every motor off. */
    fun allOff() {
        _ui.update { s -> s.copy(motors = s.motors.map { MotorState(on = false) }) }
        wake.trySend(Unit)
    }

    /**
     * "Random patterns" button. Until PATTERN_SESSION_MS has passed (or [stopPatterns] is called),
     * repeatedly turns on a random set of PATTERN_MIN_MOTORS..PATTERN_MAX_MOTORS motors for a random
     * PATTERN_MIN_MS..PATTERN_MAX_MS, with every other motor off. All motors are off when it ends.
     */
    fun startPatterns() {
        if (patternJob?.isActive == true) return
        val endsAt = SystemClock.elapsedRealtime() + PATTERN_SESSION_MS
        _ui.update { it.copy(pattern = PatternSession(endsAt), selected = null) }
        addLog("Random patterns started (${PATTERN_SESSION_MS / 60_000} min)")
        patternJob = viewModelScope.launch {
            try {
                var number = 0
                while (true) {
                    val remaining = endsAt - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    number++
                    val count = Random.nextInt(PATTERN_MIN_MOTORS, PATTERN_MAX_MOTORS + 1)
                    val fingers = Finger.entries.shuffled().take(count).sortedBy { it.ordinal }
                    // The last pattern is cut short so the session ends on time.
                    val duration = Random.nextLong(PATTERN_MIN_MS, PATTERN_MAX_MS + 1).coerceAtMost(remaining)
                    _ui.update { s ->
                        s.copy(
                            motors = Finger.entries.map { MotorState(on = it in fingers) },
                            pattern = s.pattern?.copy(
                                number = number,
                                fingers = fingers,
                                stepEndsAt = SystemClock.elapsedRealtime() + duration,
                            ),
                        )
                    }
                    addLog("Pattern $number: ${fingers.joinToString { it.label }} for ${duration / 1000.0} s")
                    wake.trySend(Unit)
                    delay(duration)
                    if (PATTERN_GAP_MS > 0) {
                        allOff()
                        delay(PATTERN_GAP_MS)
                    }
                }
                addLog("Random patterns finished")
            } finally {
                // Runs on finish AND on Stop: turn everything off and clear the session.
                _ui.update { s -> s.copy(motors = s.motors.map { MotorState(on = false) }, pattern = null) }
                wake.trySend(Unit)
                patternJob = null
            }
        }
    }

    /** "Stop" button: ends the random-pattern session early and turns all motors off. */
    fun stopPatterns() {
        if (patternJob?.isActive == true) addLog("Random patterns stopped")
        patternJob?.cancel()
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

    /** Sends one command for every motor whose state differs from what the glove last received. */
    private suspend fun flush() {
        val s = _ui.value
        val connected = ble.state.value is ConnectionState.Connected
        if (!s.demoMode && !connected) return
        for (finger in Finger.entries) {
            val desired = s.motors[finger.ordinal]
            if (desired == lastSent[finger.ordinal]) continue
            val ok = if (s.demoMode) true else ble.writeMotor(finger.ordinal, desired.on)
            if (ok) lastSent[finger.ordinal] = desired
            log(finger, desired, ok)
        }
        // If the user changed something while we were writing, go again.
        if (_ui.value.motors != s.motors) wake.trySend(Unit)
    }

    /** Adds a line to the Demo log, e.g. "Index  pin 5  ON   [1, 1, 35]". Keeps the newest 30. */
    private fun log(finger: Finger, m: MotorState, ok: Boolean) {
        val cmd = GloveProtocol.command(finger.ordinal, m.on)
        addLog("${finger.label.padEnd(6)} pin ${finger.pin}  ${if (m.on) "ON " else "OFF"}  $cmd${if (ok) "" else "  FAILED"}")
    }

    /** Adds any line to the Demo log. */
    private fun addLog(line: String) {
        _ui.update { it.copy(log = (listOf(line) + it.log).take(30)) }
    }

    /** App closing: drop the Bluetooth connection (the firmware then turns all motors off). */
    override fun onCleared() {
        ble.disconnect()
    }
}
