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

    /** App -> glove. Write-only; each write is one text command (see [command]). Matches COMMAND_UUID. */
    val COMMAND_UUID: UUID = UUID.fromString("50ec874d-2a01-4f0b-93d0-66b6217a5c30")

    /** Glove -> app. Notify-only; the glove replies with text like "START_OK". Matches CONFIRM_UUID. */
    val CONFIRM_UUID: UUID = UUID.fromString("bbc224f9-1c69-458e-ad8f-d8a9f328bb32")

    /** Glove -> app. Read + notify; 2 bytes = [battery level 0-100, charging 0|1]. Matches BATTERY_UUID. */
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
     * Text command for one motor, matching the firmware's Command enum + DeviceCommand struct:
     *   on  -> "START <motorIndex> <intensity>"   e.g. "START 1 35"
     *   off -> "STOP <motorIndex>"                e.g. "STOP 1"
     * EDIT: if the firmware team settles on a different format, change it only here.
     */
    fun command(motorIndex: Int, on: Boolean): String =
        if (on) "START $motorIndex ${MOTOR_INTENSITY.coerceIn(0, 100)}" else "STOP $motorIndex"

    /** The bytes actually written over Bluetooth: the command as plain text. */
    fun encode(motorIndex: Int, on: Boolean): ByteArray = command(motorIndex, on).toByteArray(Charsets.UTF_8)
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
