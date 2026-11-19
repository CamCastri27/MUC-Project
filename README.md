# MUC-Project — PHR 25 Haptic Glove

A glove with a vibration motor on each finger, controlled from an Android app over Bluetooth.

- **Firmware** (`Proto-UbiComp/`) runs on an ESP32-C6. It drives the 5 motors with PWM and receives commands over Bluetooth Low Energy (BLE).
- **Android app** (`android-app/`) shows a hand. Tap a finger to switch its motor on or off. Every motor runs at a fixed 35% strength. There are also **All On** / **All off** buttons, a **random patterns** mode that runs for 4 hours, and a display of the glove's battery level.

Created by Jordan Smith, Howard Mak, Auska Wang, and Camilo Castrillon.

## Contents

- [Repository layout](#repository-layout)
- [Open the test environment (emulator)](#open-the-test-environment-emulator)
- [Connect to the glove](#connect-to-the-glove)
- [Using the app](#using-the-app)
- [How it works](#how-it-works)
- [Where to change things](#where-to-change-things)
- [Troubleshooting](#troubleshooting)

## Repository layout

```
MUC-Project/
├── Proto-UbiComp/                  ESP32-C6 firmware (PlatformIO project)
│   ├── platformio.ini              board + framework settings
│   └── src/
│       ├── main.cpp                startup + motor-control task
│       ├── comm/                   Bluetooth server (UUIDs, command handling)
│       └── controls/               motor PWM
└── android-app/                    Android app (Kotlin, Jetpack Compose)
    ├── scripts/
    │   ├── setup_test_env.sh       one-time install of the Android SDK + emulator (macOS)
    │   ├── run_emulator.sh         build + launch the app on the emulator or a phone
    │   └── env.sh                  paths shared by the two scripts
    └── app/src/main/java/edu/gatech/phr25/hapticglove/
        ├── MainActivity.kt         screen layout: connection bar, hand, controls, buttons
        ├── HandView.kt             the hand drawing, motor dots, finger tapping
        ├── GloveViewModel.kt       app logic: motor state, All On / All off, Demo log
        ├── BleController.kt        Bluetooth: scan, connect, send commands, read replies
        └── GloveProtocol.kt        device name, UUIDs, command format, finger → pin list
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
2. Tap a finger and flip its switch on.
3. The dark **"Commands that would be sent to the glove"** card shows the exact text the glove would receive, e.g. `"START 1 35"`.
4. Tap **Start random patterns** to watch the pattern mode. The log also shows a line for each new pattern, e.g. `Pattern 2: Index, Ring for 7.4 s`.

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
3. The status dot turns green: **Connected to HapticGlove**. The Serial Monitor prints `Phone connected!`.
4. Tap a finger and turn its motor on. The Serial Monitor prints the command, and the app shows the glove's reply under the status line:
   ```
   Command received: START 1 35
   ```
   If the app shows **Glove replied: UNKNOWN_COMMAND** (in red), the firmware doesn't parse that command format yet. See the note under "How it works".

When the app disconnects (Disconnect button, app closed, or out of range), the firmware **turns all motors off** and starts advertising again, so you can reconnect.

Only one phone can be connected at a time.

## Using the app

### Manual control

- Tap a finger on the hand to open its card, then flip the switch to turn that motor on or off.
- **All On** turns every motor on; **All off** turns every motor off.
- A motor that's on shows a solid blue dot with a pulsing ring. Every motor runs at the same fixed strength (35%), so there is no intensity control.

### Glove battery

While connected, the glove's battery level appears at the right of the status line as a small battery icon and a percentage.

- Grey: normal. **Red:** 20% or lower. **Green with ⚡:** charging.
- The app reads the battery when it connects and then updates whenever the glove sends a new value.
- It's hidden in Demo mode and when the glove doesn't report a battery level.

**Note:** the firmware currently sets the battery value once at startup and never updates it, so the number won't change until the firmware sends real readings.

### Random patterns

Tap **Start random patterns** (below All On / All off) to start a 4-hour session:

1. The app picks **1–5 random motors** and turns them on, with every other motor off.
2. After a **random 5–10 seconds**, it picks a new set. There is no pause between patterns.
3. This repeats until **4 hours** have passed. Then all motors turn off and the session ends by itself.

While a session runs:

- The text above the hand reads **"Random patterns currently running."** and the hand shows which motors are on.
- The card shows only a countdown of the time left (e.g. `3:59:48`) and a **Stop** button. **Stop** ends the session early and turns all motors off.
- Manual controls are locked: tapping fingers does nothing, and All On / All off are greyed out.
- The phone's screen stays on until the session ends.

For a full 4-hour run:

- **Keep the app open and in front, and keep the phone plugged in.** If you switch to another app or lock the phone, Android may pause the timers.
- If the Bluetooth connection drops, the patterns keep running in the app. Tap **Connect** again and the app sends the current pattern to the glove.

The 4 hours, 1–5 motors, 5–10 seconds and the pause between patterns can all be changed at the top of `GloveViewModel.kt` (see [Where to change things](#where-to-change-things)).

## How it works

```
 Android app                      Bluetooth LE                    ESP32-C6 firmware
 tap finger on/off    ──►  text command per motor change   ──►  queue → motor PWM
 status line          ◄──  text reply ("START_OK", …)       ◄──  sendConfirmation()
 battery display      ◄──  2 bytes [level, charging]       ◄──  battery characteristic
```

| | |
|---|---|
| Device name | `HapticGlove` |
| Service UUID | `bee1b769-d394-4dde-8dfa-8117e8b231ac` |
| Command characteristic (app → glove, write) | `50ec874d-2a01-4f0b-93d0-66b6217a5c30` |
| Confirm characteristic (glove → app, notify) | `bbc224f9-1c69-458e-ad8f-d8a9f328bb32` |
| Battery characteristic (glove → app, read + notify) | `5e6fc8e7-34a8-4c3a-a32b-54994614007d`. 2 bytes: `[level 0–100, charging 0/1]` |
| Motor on | `START <motor index> <intensity>`, e.g. `START 1 35`. The app always sends intensity 35. |
| Motor off | `STOP <motor index>`, e.g. `STOP 1` |
| Glove replies | `START_OK`, `STOP_OK`, or `UNKNOWN_COMMAND` |

**Note:** as of Nov 19, 2026, the firmware's `CommandCallbacks::onWrite` only accepts the exact text `START` or `STOP`, without a motor index, and doesn't drive the motors yet (both are `TODO`). It needs to split the command into its `DeviceCommand` fields (`command`, `motor`, `intensity`) and put it on `commandQueue`. If the team picks a different text format, change `GloveProtocol.command()` in the app to match.

| Motor index | Finger | ESP32 pin |
|---|---|---|
| 0 | Thumb | 4 |
| 1 | Index | 5 |
| 2 | Middle | 6 |
| 3 | Ring | 7 |
| 4 | Pinky | 8 |

**Intensity to PWM:** the motors don't spin below a PWM value of about 57, so `Motor::setIntensity` in `controls.cpp` maps 1–100% to PWM 57–120.

**Why PWM:** each motor draws about 85 mA, so the motors are switched through transistors. The ESP32-C6 has no DAC, so intensity is set with PWM.

**Sending:** the app sends only the motors that changed, and waits for each write to be acknowledged before sending the next one, so the Bluetooth link never backs up.

## Where to change things

| I want to change… | File | What to edit |
|---|---|---|
| Which pin drives which finger | `Proto-UbiComp/src/main.cpp` **and** `GloveProtocol.kt` | `pins[]` and the `Finger` list. Keep the same order in both. |
| Weakest / strongest vibration | `main.cpp` | `MIN_DUTY`, `MAX_DUTY` |
| Bluetooth name or UUIDs | `comm/communciations.h` **and** `GloveProtocol.kt` | `DEVICE_NAME`, `SERVICE_UUID`, `COMMAND_UUID`, `CONFIRM_UUID`, `BATTERY_UUID`. They must match. |
| Test the motors without the app | `main.cpp` | Set `SWEEP_TEST` to `1` |
| Motor strength (fixed for all motors) | `GloveProtocol.kt` | `MOTOR_INTENSITY` (currently 35) |
| Text format of motor commands | `GloveProtocol.kt` | `command()` |
| Hand shape, finger positions, motor dot positions | `HandView.kt` | `fingerShapes` table (explained at the top of the file) |
| Curves of the hand outline | `HandView.kt` | `palmPath` (each curve is commented) |
| Colors | `HandView.kt` / `MainActivity.kt` | Color constants marked `EDIT:` |
| Screen layout, button labels | `MainActivity.kt` | `GloveScreen()` |
| Random patterns: session length | `GloveViewModel.kt` | `PATTERN_SESSION_MS` (4 hours) |
| Random patterns: how many motors per pattern | `GloveViewModel.kt` | `PATTERN_MIN_MOTORS`, `PATTERN_MAX_MOTORS` (1–5) |
| Random patterns: how long each pattern lasts | `GloveViewModel.kt` | `PATTERN_MIN_MS`, `PATTERN_MAX_MS` (5–10 s) |
| Random patterns: pause between patterns | `GloveViewModel.kt` | `PATTERN_GAP_MS` (0 = none) |
| Random patterns: on-screen text, timer size | `MainActivity.kt` | `PatternCard()` and the hint text above `HandView` |
| Battery turns red below… | `MainActivity.kt` | `LOW_BATTERY` (20%) |
| Add a new action (e.g. a vibration pattern) | `GloveViewModel.kt` + `MainActivity.kt` | See the comment at the top of `GloveViewModel.kt` |
| Bluetooth timeouts | `BleController.kt` | `SCAN_TIMEOUT_MS`, `WRITE_TIMEOUT_MS` |

## Troubleshooting

| Problem | Fix |
|---|---|
| Connect fails on the emulator | Expected: the emulator can't reach the glove. Turn on **Demo**. |
| "Glove not found…" | Check the ESP32 is powered and the Serial Monitor shows `Advertising as HapticGlove`. Another phone may already be connected; disconnect it first. |
| "Connected, but the glove's command service was not found" | The ESP32 is running old firmware. Upload `Proto-UbiComp` again. |
| Tapping **Connect** does nothing | Bluetooth permission was denied. Go to Settings → Apps → Haptic Glove → Permissions and allow **Nearby devices** (or **Location** on Android 11 and older). |
| Motor is on in the app but doesn't vibrate | Check the Serial Monitor for the `Command received: …` line and the app for **Glove replied: UNKNOWN_COMMAND** (see the note under [How it works](#how-it-works)). If the command arrives and is understood, the problem is wiring or power. Try raising `MOTOR_INTENSITY` in `GloveProtocol.kt`, since low duty values may not overcome the motor's startup friction. |
| `run_emulator.sh` says the emulator isn't found | Run `./scripts/setup_test_env.sh` first. |
| Emulator window never appears | See the log at `/tmp/hapticglove-emulator.log`. |
| No battery shown | The glove hasn't reported a battery level, or you're in Demo mode. The battery only shows while connected. |
| Random patterns stopped early or drifted | The app was in the background or the phone slept. Keep the app open and the phone plugged in for the whole session. |
| `run_emulator.sh --device` fails with "more than one device" | The emulator is still open. Close it (`adb emu kill`) and run the command again. |
| `adb: no devices` with a phone | Re-plug the cable, accept the USB debugging prompt on the phone, and check `adb devices` lists it. |
