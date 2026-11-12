# MUC-Project — PHR 25 Haptic Glove

A glove with a vibration motor on each finger, controlled from an Android app over Bluetooth.

- **Firmware** (`Proto-UbiComp/`) runs on an ESP32-C6. It drives the 5 motors with PWM and receives commands over Bluetooth Low Energy (BLE).
- **Android app** (`android-app/`) shows a hand. Tap a finger to switch its motor on or off and set its intensity with a slider. There are also **All On** (every motor at 40%) and **All off** buttons.

Created by Jordan Smith, Howard Mak, Auska Wang, and Camilo Castrillon.

## Contents

- [Repository layout](#repository-layout)
- [Open the test environment (emulator)](#open-the-test-environment-emulator)
- [Connect to the glove](#connect-to-the-glove)
- [How it works](#how-it-works)
- [Where to change things](#where-to-change-things)
- [Troubleshooting](#troubleshooting)

## Repository layout

```
MUC-Project/
├── Proto-UbiComp/                  ESP32-C6 firmware (PlatformIO project)
│   ├── platformio.ini              board + framework settings
│   └── src/main.cpp                motor control + Bluetooth server
└── android-app/                    Android app (Kotlin, Jetpack Compose)
    ├── scripts/
    │   ├── setup_test_env.sh       one-time install of the Android SDK + emulator (macOS)
    │   ├── run_emulator.sh         build + launch the app on the emulator or a phone
    │   └── env.sh                  paths shared by the two scripts
    └── app/src/main/java/edu/gatech/phr25/hapticglove/
        ├── MainActivity.kt         screen layout: connection bar, hand, controls, buttons
        ├── HandView.kt             the hand drawing, motor dots, finger tapping
        ├── GloveViewModel.kt       app logic: motor state, All On / All off, Demo log
        ├── BleController.kt        Bluetooth: scan, connect, send packets
        └── GloveProtocol.kt        device name, UUIDs, packet format, finger → pin list
```

Every source file starts with a short guide, and the values you're likely to tweak are marked `EDIT:`. Search for `EDIT:` to find them.

## Open the test environment (emulator)

The emulator lets you try the app on your computer without a phone or glove. It can't reach the glove over Bluetooth, so you use the app's **Demo** mode.

### macOS (scripted)

Requires [Homebrew](https://brew.sh).

```bash
cd android-app

# One time only: installs JDK 17, the Android SDK and an emulator called "HapticGlove_Pixel".
# Downloads a few GB.
./scripts/setup_test_env.sh

# Every time: builds the app, starts the emulator if needed, installs and opens the app.
./scripts/run_emulator.sh
```

The first boot of the emulator takes a minute or two. After that, rerun `./scripts/run_emulator.sh` whenever you change the code. It reuses the emulator if it's already open.

In the app:

1. Turn on the **Demo** switch at the top.
2. Tap a finger, flip its switch on, and drag the slider.
3. The dark **"Packets that would be sent to the glove"** card shows the exact bytes the glove would receive.

To close the emulator, close its window or run `adb emu kill`.

### Any OS (Android Studio)

1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** and choose the `android-app/` folder. Let Gradle sync finish.
3. **Device Manager → Create device** (any phone, API 26 or newer). Then press **Run ▶**.

## Connect to the glove

You need the glove (ESP32-C6 with motors), a USB cable, and a **real Android phone** running Android 8.0 or newer with Bluetooth. The emulator can't connect to the glove.

### 1. Flash the firmware

1. Open `Proto-UbiComp/` in VS Code with the **PlatformIO** extension installed.
2. Plug in the ESP32-C6 and click **Upload** (→ in the PlatformIO toolbar). From a terminal, you can instead run `pio run -t upload` inside `Proto-UbiComp/`.
3. Open the **Serial Monitor** (plug icon, 115200 baud). You should see:
   ```
   Advertising as HapticGlove
   ```

### 2. Install the app on your phone

1. On the phone, turn on **Developer options**: Settings → About phone → tap **Build number** 7 times.
2. In Settings → Developer options, turn on **USB debugging**.
3. Plug the phone into your computer and accept the "Allow USB debugging?" prompt on the phone.
4. Install the app:
   - macOS: `cd android-app && ./scripts/run_emulator.sh --device`
   - Android Studio: pick your phone in the device dropdown and press **Run ▶**.

Once installed, you can unplug the phone. The app stays on it.

### 3. Connect

1. Make sure Bluetooth is on and **Demo** is off.
2. Tap **Connect**. The first time, Android asks for **Nearby devices** permission (Android 12+) or **Location** (Android 11 and older). Allow it.
3. The status dot turns green: **Connected to HapticGlove**. The Serial Monitor prints `App connected`.
4. Tap a finger and turn its motor on. Each change prints a line on the Serial Monitor:
   ```
   Motor 1 (pin 5): ON 40% -> duty 135
   ```

When the app disconnects (Disconnect button, app closed, or out of range), the firmware **turns all motors off** and starts advertising again, so you can reconnect.

Only one phone can be connected at a time.

## How it works

```
 Android app                      Bluetooth LE                    ESP32-C6 firmware
 tap finger / slider  ──►  3-byte packet per motor change  ──►  analogWrite(pin, PWM)
```

| | |
|---|---|
| Device name | `HapticGlove` |
| Service UUID | `8f1d0001-6b5a-4c2e-9a3e-5c1f0e7a2b10` |
| Motor characteristic (write) | `8f1d0002-6b5a-4c2e-9a3e-5c1f0e7a2b10` |
| Packet | 3 bytes: `[motor index, on (0/1), intensity (0–100)]` |

| Motor index | Finger | ESP32 pin |
|---|---|---|
| 0 | Thumb | 4 |
| 1 | Index | 5 |
| 2 | Middle | 6 |
| 3 | Ring | 7 |
| 4 | Pinky | 8 |

**Intensity to PWM:** the motors don't spin below a PWM value of about 57, so the firmware maps 1–100% to PWM 57–255. 0% or "off" means PWM 0. For example, 40% → 135.

**Why PWM:** each motor draws about 85 mA, so the motors are switched through transistors. The ESP32-C6 has no DAC, so intensity is set with PWM.

**Packets while dragging:** the app sends only the motors that changed. While you drag a slider it waits for each packet to be acknowledged before sending the next one, so the Bluetooth link never backs up.

## Where to change things

| I want to change… | File | What to edit |
|---|---|---|
| Which pin drives which finger | `Proto-UbiComp/src/main.cpp` **and** `GloveProtocol.kt` | `pins[]` and the `Finger` list. Keep the same order in both. |
| Weakest / strongest vibration | `main.cpp` | `MIN_DUTY`, `MAX_DUTY` |
| Bluetooth name or UUIDs | `main.cpp` **and** `GloveProtocol.kt` | `DEVICE_NAME`, `SERVICE_UUID`, `MOTOR_CHAR_UUID`. They must match. |
| Test the motors without the app | `main.cpp` | Set `SWEEP_TEST` to `1` |
| "All On" level | `MainActivity.kt` | `ALL_ON_INTENSITY` |
| Starting slider value | `GloveProtocol.kt` | `MotorState(intensity = 50)` |
| Hand shape, finger positions, motor dot positions | `HandView.kt` | `fingerShapes` table (explained at the top of the file) |
| Curves of the hand outline | `HandView.kt` | `palmPath` (each curve is commented) |
| Colors | `HandView.kt` / `MainActivity.kt` | Color constants marked `EDIT:` |
| Screen layout, button labels | `MainActivity.kt` | `GloveScreen()` |
| Add a new action (e.g. a vibration pattern) | `GloveViewModel.kt` + `MainActivity.kt` | See the comment at the top of `GloveViewModel.kt` |
| Bluetooth timeouts | `BleController.kt` | `SCAN_TIMEOUT_MS`, `WRITE_TIMEOUT_MS` |

## Troubleshooting

| Problem | Fix |
|---|---|
| Connect fails on the emulator | Expected: the emulator can't reach the glove. Turn on **Demo**. |
| "Glove not found…" | Check the ESP32 is powered and the Serial Monitor shows `Advertising as HapticGlove`. Another phone may already be connected; disconnect it first. |
| "Connected, but the glove's motor service was not found" | The ESP32 is running old firmware. Upload `Proto-UbiComp` again. |
| Tapping **Connect** does nothing | Bluetooth permission was denied. Go to Settings → Apps → Haptic Glove → Permissions and allow **Nearby devices** (or **Location** on Android 11 and older). |
| Motor is on in the app but doesn't vibrate | Check the Serial Monitor for the `Motor … -> duty …` line. If it's there, the problem is wiring or power. Try a higher intensity, since low duty values may not overcome the motor's startup friction. |
| `run_emulator.sh` says the emulator isn't found | Run `./scripts/setup_test_env.sh` first. |
| Emulator window never appears | See the log at `/tmp/hapticglove-emulator.log`. |
| `adb: no devices` with a phone | Re-plug the cable, accept the USB debugging prompt on the phone, and check `adb devices` lists it. |
