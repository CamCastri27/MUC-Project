#ifndef CONTROLS_H
#define CONTROLS_H

#include <Arduino.h>

typedef struct {
    uint8_t batteryLevel;
    bool isCharging;
} BatteryInfo;

extern BatteryInfo batteryStats;

class Motor {
    public:
        Motor(int amount, uint8_t startingPin);
        Motor(uint8_t amount, uint8_t pin0, uint8_t pin1, uint8_t pin2, uint8_t pin3, uint8_t pin4);
        void setIntensity(int idx, uint8_t intensity);
        void freeze(); 
    private:
        void init();
        static constexpr uint8_t MAX_MOTORS = 5;
        uint8_t numMotors;
        uint8_t pins[MAX_MOTORS];
        bool initialized[MAX_MOTORS];
};

#endif