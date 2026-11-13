#include <controls/controls.h>

#define MOTOR_PWM_FREQ 20000
#define MOTOR_PWM_RESOLUTION 8

Motor::Motor(int amount = 5, uint8_t startingPin = 13){
    numMotors = amount;
}

Motor::Motor(uint8_t amount, uint8_t pin0 = 0, uint8_t pin1 = 0, uint8_t pin2 = 0, uint8_t pin3 = 0, uint8_t pin4 = 0){
    numMotors = min(amount, MAX_MOTORS);

    uint8_t motorPins[MAX_MOTORS] = {pin0, pin1, pin2, pin3, pin4};

    for (uint8_t i = 0; i < MAX_MOTORS; i++){
        pins[i] = motorPins[i];
        initialized[i] = false;
    }

    init();
}

void Motor::init(){
    for (int i = 0; i < numMotors; i++){
        initialized[i] = ledcAttach(pins[i], MOTOR_PWM_FREQ, MOTOR_PWM_RESOLUTION);

        if (initialized[i]) ledcWrite(pins[i], 0);
        else Serial.printf("Init failed for GPIO %d\n", pins[i]);
    }
}

void Motor::setIntensity(int idx, uint8_t intensity){
    if (idx >= numMotors || !initialized[idx]) return;
    ledcWrite(idx, intensity);  
}

void Motor::freeze(){
    for (int i = 0; i < numMotors; i++){
        if (initialized[i]) ledcWrite(pins[i], 0);
    }
}