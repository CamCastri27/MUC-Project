


// /*

// #include <comm/communciations.h>
// #include <controls/controls.h>
// /*
// This is a general purpose piece of firmware designed for PHR team 25.
// The motors each draw 85mA of current, so we are using a transistor to power them. Of course,
// this makes analog voltages impossible to do, not to mention the ESP32-C6 doesn't contain a DAC.
// To get around this, we are using Pulse Width Modulation (PWM) to control vibrational intensity.

// The Android app (android-app/) connects over Bluetooth LE and writes 3-byte packets to the
// motor characteristic: [motorIndex 0-4, on 0|1, intensity 0-100].
// Motor index order matches pins[]: thumb, index, middle, ring, pinky.
// The UUIDs and device name must match android-app/.../GloveProtocol.kt.

// Created by Jordan Smith, Howard Mak, Auska Wang, and Camilo Castrillon
// */

// // Function Prototypes
// void controls(void* params);
// void communications(void* params);


// // We can pick the pins here:
// #define THUMB 0
// #define INDEX 0
// #define MIDDLE 0
// #define RING 0
// #define PINKY 0


// TaskHandle_t controlsTaskHandle = nullptr;
// TaskHandle_t communicationsTaskHandle = nullptr;

// QueueHandle_t commandQueue = nullptr;

// BLEServer* pServer = nullptr;

// BLECharacteristic* pConfigCharacteristic = nullptr;
// BLECharacteristic* pCommandCharacteristic = nullptr;
// BLECharacteristic* pConfirmCharacteristic = nullptr;
// Motor motors(5, THUMB, INDEX, MIDDLE, RING, PINKY);

// void setup() {
//   // put your setup code here, to run once:
//   initBLE();
//   //motors.setIntensity(0, 255);

//   //commandQueue = xQueueCreate(5, sizeof(DeviceCommand));

//   //xTaskCreatePinnedToCore(controls, "Motor Control", 4096, nullptr, 2, &controlsTaskHandle, 1);
//   //xTaskCreatePinnedToCore(communications, "BLE Communication", 8192, nullptr, 1, &communicationsTaskHandle, 0);

// }

// /*
// For anyone who doesn't understand whats going on here, the tasks are running on 2 different
// cores at the same time. I have to use a way to communciate between the threads. This could be
// done using a semaphore mutex or semaphore queue. Queue seems better considering "communications"
// controls "controls" without any expectation that controls needs to send anything back.
// */
// void controls(void* params){
//     DeviceCommand command;
//     while (1){
//         // listen to comm and control motors
//         if (xQueueReceive(commandQueue, &command, 0) == pdTRUE){
//             // process command
//             switch (command.command){
//                 case FREEZE:
//                     motors.freeze();
//                     break;

//                 case START:
//                     motors.setIntensity(command.motor, command.intensity);
//                     break;

//                 case STOP:
//                     motors.setIntensity(command.motor, 0);
//                     break;

//                 default:
//                     break;
//             }
//         }
//     }
// }

// void communications(void* params){
//     DeviceCommand command;
//     while (1){
//         // do comm stuff between self and phone. translate to device command for controls
//         if (xQueueSend(commandQueue, &command, 0) == pdTRUE){
//             Serial.println("Command queued!");
//         }
//     }
// }

// // Because we are threading, theres no need to use loop anymore. Kinda just want to avoid a messy super loop anyways.
// void loop(){}


