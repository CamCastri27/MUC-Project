#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
/*
This is a general purpose piece of firmware designed for PHR team 25.
The motors each draw 85mA of current, so we are using a transistor to power them. Of course,
this makes analog voltages impossible to do, not to mention the ESP32-C6 doesn't contain a DAC.
To get around this, we are using Pulse Width Modulation (PWM) to control vibrational intensity.

The Android app (android-app/) connects over Bluetooth LE and writes 3-byte packets to the
motor characteristic: [motorIndex 0-4, on 0|1, intensity 0-100].
Motor index order matches pins[]: thumb, index, middle, ring, pinky.
The UUIDs and device name must match android-app/.../GloveProtocol.kt.

Created by Jordan Smith, Howard Mak, Auska Wang, and Camilo Castrillon

WHERE TO EDIT:
  Which GPIO pin drives which finger ..... pins[]
  Weakest / strongest vibration .......... MIN_DUTY / MAX_DUTY
  Bluetooth name and IDs ................. DEVICE_NAME / SERVICE_UUID / MOTOR_CHAR_UUID
  Test motors without the app ............ SWEEP_TEST
Debug output: open the PlatformIO Serial Monitor at 115200 baud to see every command received.

*/

// EDIT: GPIO pin for each motor, in order: thumb, index, middle, ring, pinky.
// The app sends a motor index (0-4) that points into this array, so keep this order in sync
// with the Finger list in android-app/.../GloveProtocol.kt.
const u8_t pins[5] = {4, 5, 6, 7, 8};
const u8_t NUM_MOTORS = sizeof(pins) / sizeof(pins[0]);

// EDIT: PWM range used for intensity 1-100% (PWM values go 0-255).
// Below MIN_DUTY the motors don't spin up (from the original sweep test, which started at 57).
// Lower MAX_DUTY if 100% is too strong or the motors draw too much current.
const int MIN_DUTY = 57;
const int MAX_DUTY = 255;

// Bluetooth identity. IMPORTANT: these must match GloveProtocol.kt in the Android app.
#define DEVICE_NAME       "HapticGlove"
#define SERVICE_UUID      "bee1b769-d394-4dde-8dfa-8117e8b231ac"
#define MOTOR_CHAR_UUID   "8f1d0002-6b5a-4c2e-9a3e-5c1f0e7a2b10"

// EDIT: set to 1 to run the old "sweep every motor" test instead of waiting for the app.
// Bluetooth is not started in this mode.
#define SWEEP_TEST 0

// Shared between the Bluetooth callbacks (which receive commands) and loop() (which drives the pins).
// The callbacks only store the new value; loop() does the actual analogWrite.
volatile int targetDuty[5] = {0, 0, 0, 0, 0};
volatile bool dutyChanged = false;
volatile bool clientConnected = false;

// Converts an app command (on/off + 0-100%) into a PWM value (0 or MIN_DUTY..MAX_DUTY).
// e.g. 40% -> map(40, 1, 100, 57, 255) = 135.
int intensityToDuty(bool on, int intensity) {
  if (!on || intensity <= 0) return 0;
  if (intensity > 100) intensity = 100;
  return map(intensity, 1, 100, MIN_DUTY, MAX_DUTY);
}

// Runs every time the app sends a packet: 3 bytes = [motorIndex, on (0/1), intensity 0-100].
class MotorCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    uint8_t *data = c->getData();
    size_t len = c->getLength();
    if (len != 3) {
      Serial.printf("Ignoring packet of length %u\n", (unsigned)len);
      return;
    }
    uint8_t idx = data[0];
    bool on = data[1] != 0;
    int intensity = data[2];
    if (idx >= NUM_MOTORS) return;
    targetDuty[idx] = intensityToDuty(on, intensity);
    dutyChanged = true;
    Serial.printf("Motor %u (pin %u): %s %d%% -> duty %d\n", idx, pins[idx], on ? "ON" : "OFF", intensity, targetDuty[idx]);
  }
};

// Runs when the phone connects or disconnects.
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
    clientConnected = true;
    Serial.println("App connected");
  }
  void onDisconnect(BLEServer *s) override {
    clientConnected = false;
    // Safety: never leave motors buzzing with nobody in control.
    for (int i = 0; i < NUM_MOTORS; i++) targetDuty[i] = 0;
    dutyChanged = true;
    Serial.println("App disconnected, motors off, advertising again");
    BLEDevice::startAdvertising();
  }
};

// Starts Bluetooth: creates the motor service the app writes to and starts advertising
// so the app's Connect button can find the glove.
void setupBle() {
  BLEDevice::init(DEVICE_NAME);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);
  BLECharacteristic *motorChar = service->createCharacteristic(
      MOTOR_CHAR_UUID, BLECharacteristic::PROPERTY_WRITE);
  motorChar->setCallbacks(new MotorCallbacks());
  service->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("Advertising as " DEVICE_NAME);
}

void setup() {
  Serial.begin(115200);
  // Change the pins to be outputs
  for (u8_t pin : pins) {
    pinMode(pin, OUTPUT);
    analogWrite(pin, 0);
  }
#if !SWEEP_TEST
  setupBle();
#endif
}

void loop() {
#if SWEEP_TEST
  for (u8_t pin: pins){
    for (int i = MIN_DUTY; i < 120; i++){
      analogWrite(pin, i);
      delay(10);
    }
    analogWrite(pin, 0);
  }
#else
  // Apply any new motor values received over Bluetooth.
  if (dutyChanged) {
    dutyChanged = false;
    for (int i = 0; i < NUM_MOTORS; i++) analogWrite(pins[i], targetDuty[i]);
  }
  delay(5);
#endif
}