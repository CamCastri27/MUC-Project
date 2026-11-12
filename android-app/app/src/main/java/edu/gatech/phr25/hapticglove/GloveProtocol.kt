package edu.gatech.phr25.hapticglove

import java.util.UUID

/**
 * Shared contract between this app and the ESP32-C6 firmware (Proto-UbiComp/src/main.cpp).
 * IMPORTANT: if you change anything in this object, make the same change in main.cpp,
 * or the app won't find the glove / the glove won't understand the packets.
 */
object GloveProtocol {
    /** Name the ESP32 advertises over BLE. Matches DEVICE_NAME in main.cpp. */
    const val DEVICE_NAME = "HapticGlove"

    /** Matches SERVICE_UUID in main.cpp. */
    val SERVICE_UUID: UUID = UUID.fromString("8f1d0001-6b5a-4c2e-9a3e-5c1f0e7a2b10")

    /** Write-only characteristic (MOTOR_CHAR_UUID in main.cpp). Each write is 3 bytes: [motorIndex, on(0|1), intensity(0..100)]. */
    val MOTOR_CHAR_UUID: UUID = UUID.fromString("8f1d0002-6b5a-4c2e-9a3e-5c1f0e7a2b10")

    /** Builds one packet. The firmware's MotorCallbacks::onWrite reads these same 3 bytes. */
    fun encode(motorIndex: Int, on: Boolean, intensity: Int): ByteArray = byteArrayOf(
        motorIndex.toByte(),
        (if (on) 1 else 0).toByte(),
        intensity.coerceIn(0, 100).toByte(),
    )
}

/**
 * The five motors. The ORDER here is the motor index sent to the glove, so it must match the
 * order of the firmware's pins[] array: pins = {4, 5, 6, 7, 8}.
 * EDIT: `label` is the name shown in the app; `pin` is only for display ("ESP32 pin 5").
 * If your wiring is different, change the pin numbers here AND in pins[] in main.cpp.
 */
enum class Finger(val label: String, val pin: Int) {
    THUMB("Thumb", 4),
    INDEX("Index", 5),
    MIDDLE("Middle", 6),
    RING("Ring", 7),
    PINKY("Pinky", 8),
}

/**
 * State of one motor.
 * EDIT: intensity = 50 is the starting slider value for every finger when the app opens.
 */
data class MotorState(val on: Boolean = false, val intensity: Int = 50)
