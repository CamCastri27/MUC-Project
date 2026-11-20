package edu.gatech.phr25.hapticglove

import java.util.UUID

/**
 * Shared contract between this app and the ESP32-C6 firmware
 * (Proto-UbiComp/src/comm/communciations.h and .cpp).
 * IMPORTANT: if you change anything in this object, make the same change in the firmware,
 * or the app won't find the glove / the glove won't understand the commands.
 */
object GloveProtocol {
    /** Name the ESP32 advertises over BLE. Matches DEVICE_NAME in communciations.h. */
    const val DEVICE_NAME = "HapticGlove"

    /** Matches SERVICE_UUID in communciations.h. */
    val SERVICE_UUID: UUID = UUID.fromString("bee1b769-d394-4dde-8dfa-8117e8b231ac")

    /**
     * App -> glove. Write-only; each write is one 3-byte motor packet (see [encode]).
     * Matches MOTOR_CHAR_UUID in Proto-UbiComp/src/main.cpp (the firmware currently on the glove).
     * The newer comm/ firmware uses COMMAND_UUID "50ec874d-2a01-4f0b-93d0-66b6217a5c30" with text
     * commands instead; switch back to that UUID and format if the team moves to that firmware.
     */
    val COMMAND_UUID: UUID = UUID.fromString("8f1d0002-6b5a-4c2e-9a3e-5c1f0e7a2b10")

    /** Glove -> app. Notify-only; the glove replies with text like "START_OK". Matches CONFIRM_UUID.
     *  Optional: main.cpp doesn't have it yet, so the app skips it (no replies shown). */
    val CONFIRM_UUID: UUID = UUID.fromString("bbc224f9-1c69-458e-ad8f-d8a9f328bb32")

    /** Glove -> app. Read + notify; 2 bytes = [battery level 0-100, charging 0|1]. Matches BATTERY_UUID.
     *  Optional: main.cpp doesn't have it yet, so the app skips it (no battery shown). */
    val BATTERY_UUID: UUID = UUID.fromString("5e6fc8e7-34a8-4c3a-a32b-54994614007d")

    /** Standard BLE descriptor the app writes to turn on notifications (the BLE2902 in the firmware). */
    val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Fixed strength (0-100%) every motor runs at when it's on. Users can only turn motors on/off.
     * EDIT: change this number to make all motors stronger or weaker.
     */
    const val MOTOR_INTENSITY = 35

    /** Reply the firmware sends when it doesn't recognise a command. */
    const val UNKNOWN_REPLY = "UNKNOWN_COMMAND"

    /**
     * The bytes written over Bluetooth for one motor, matching MotorCallbacks in main.cpp:
     *   3 bytes = [motorIndex 0-4, on 0|1, intensity 0-100]   e.g. [1, 1, 35] or [1, 0, 0]
     * EDIT: if the firmware team settles on a different format, change it only here.
     */
    fun encode(motorIndex: Int, on: Boolean): ByteArray = byteArrayOf(
        motorIndex.toByte(),
        (if (on) 1 else 0).toByte(),
        (if (on) MOTOR_INTENSITY.coerceIn(0, 100) else 0).toByte(),
    )

    /** Readable form of [encode] for the Demo log, e.g. "[1, 1, 35]". */
    fun command(motorIndex: Int, on: Boolean): String =
        encode(motorIndex, on).joinToString(prefix = "[", postfix = "]") { (it.toInt() and 0xFF).toString() }
}

/**
 * The five motors. The ORDER here is the motor index sent to the glove, so it must match the
 * order the firmware passes pins to Motor(...) in main.cpp: THUMB, INDEX, MIDDLE, RING, PINKY.
 * EDIT: `label` is the name shown in the app; `pin` is only for display ("ESP32 pin 5").
 * If your wiring changes, update the pin numbers here AND the #defines in main.cpp.
 */
enum class Finger(val label: String, val pin: Int) {
    THUMB("Thumb", 4),
    INDEX("Index", 5),
    MIDDLE("Middle", 6),
    RING("Ring", 7),
    PINKY("Pinky", 8),
}

/** State of one motor: just on or off (strength is always GloveProtocol.MOTOR_INTENSITY). */
data class MotorState(val on: Boolean = false)
