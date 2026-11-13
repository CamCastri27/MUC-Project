#include <comm/communciations.h>

/*
This is the implementation of the classes in communications.h. Please don't judge too harshly, this is my first actual C++ program where I didn't just write C style.

*/
bool deviceConnected = false;
bool oldDeviceConnected = false;

// CONNECTION CALLBACKS
void MyServerCallbacks::onConnect(BLEServer* pServer){
    deviceConnected = true;
    Serial.println("Phone connected!");
}

void MyServerCallbacks::onDisconnect(BLEServer* pServer){
    deviceConnected = false;
    Serial.println("Phone disconnected!");
}


// COMMAND CALLBACK
void CommandCallbacks::onWrite(BLECharacteristic* pCharacteristic){
    String command = pCharacteristic->getValue().c_str();
    Serial.print("Command received: ");
    Serial.println(command);
    if (command == "START"){
      // TODO: Start device operation
      sendConfirmation("START_OK");

    }
    else if (command == "STOP"){
      // TODO: Stop device operation
      sendConfirmation("STOP_OK");

    }
    else {
      sendConfirmation("UNKNOWN_COMMAND");
    }
}


// SEND CONFIRMATION
void sendConfirmation(String message) {
  if (!deviceConnected) return;
  pConfirmCharacteristic->setValue(message.c_str());
  pConfirmCharacteristic->notify();
}

// INIT CONNECTION
void initBLE(){
    BLEDevice::init(DEVICE_NAME);

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());
    // make the bluetooth service
    BLEService* pService = pServer->createService(SERVICE_UUID);

    // The goal is to use 3 different characteristics to handle:
    // - Commands
    pCommandCharacteristic = pService->createCharacteristic(COMMAND_UUID, BLECharacteristic::PROPERTY_WRITE);
    pCommandCharacteristic->setCallbacks(new CommandCallbacks());
    // - Confirmation
    pConfirmCharacteristic = pService->createCharacteristic(CONFIRM_UUID, BLECharacteristic::PROPERTY_NOTIFY);
    pConfirmCharacteristic->addDescriptor(new BLE2902());
    // - Battery Life
    pBatteryCharacteristic = pService->createCharacteristic(BATTERY_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    pBatteryCharacteristic->addDescriptor(new BLE2902());
    //      - provide initial battery information
    uint8_t batteryData[2] = {
        batteryStats.batteryLevel,
        batteryStats.isCharging ? 1 : 0
    };
    pBatteryCharacteristic->setValue(batteryData, 2);

    // Start the service
    pService->start();
    

    // Finally, we can start BLE advertising
    BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();

    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);

    BLEDevice::startAdvertising();
}
