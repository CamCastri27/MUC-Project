#ifndef COMM_H
#define COMM_H

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <controls/controls.h>

#define DEVICE_NAME         "HapticGlove"
#define SERVICE_UUID        "bee1b769-d394-4dde-8dfa-8117e8b231ac"
#define COMMAND_UUID        "50ec874d-2a01-4f0b-93d0-66b6217a5c30"
#define CONFIRM_UUID        "bbc224f9-1c69-458e-ad8f-d8a9f328bb32"
#define BATTERY_UUID        "5e6fc8e7-34a8-4c3a-a32b-54994614007d"


typedef enum {
    FREEZE,
    STOP,
    START
} Command;

typedef struct {
    Command command; // i.e. FREEZE, START, STOP
    uint8_t motor; // which motor this applies to (FREEZE applies to all of them)
    uint8_t intensity; // PWM frequency (STOP or FREEZE == 0)
} DeviceCommand;

extern DeviceCommand command;




// GLOBAL VARIABLES
extern BLEServer* pServer;
extern BLECharacteristic* pCommandCharacteristic;
extern BLECharacteristic* pConfirmCharacteristic;
extern BLECharacteristic* pBatteryCharacteristic;

extern bool deviceConnected;
extern bool oldDeviceConnected;

// CALLBACKS
class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer);
  void onDisconnect(BLEServer* pServer);
};


class CommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pCharacteristic);
};

// GENERAL FUNCTIONS
void sendConfirmation(String message);
void initBLE();

#endif