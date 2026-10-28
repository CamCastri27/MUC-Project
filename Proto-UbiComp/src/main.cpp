#include <Arduino.h>
/*
This is a general purpose piece of firmware designed for PHR team 25.
The motors each draw 85mA of current, so we are using a transistor to power them. Of course,
this makes analog voltages impossible to do, not to mention the ESP32-C6 doesn't contain a DAC.
To get around this, we are using Pulse Width Modulation (PWM) to control vibrational intensity.

Created by Jordan Smith, Howard Mak, Auska Wang, and Camilo

*/ 
const u8_t pins[5] = {4, 5, 6, 7, 8};


void setup() {
  // Change the pins to be outputs
  for (u8_t pin : pins) pinMode(pin, OUTPUT);
}

void loop() {
  // put your main code here, to run repeatedly:
  //analogWrite(pins[0], 255); // behind the scenes it calls: ledcWrite(pin, value);
  for (u8_t pin: pins){
    for (int i = 57; i < 120; i++){
      analogWrite(pin, i);
      delay(10);
    }
    analogWrite(pin, 0);
  }
}
