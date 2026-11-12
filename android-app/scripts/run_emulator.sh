#!/usr/bin/env bash
# Builds the app, boots the emulator (if needed), installs, and launches.
# Usage: ./scripts/run_emulator.sh            -> emulator
#        ./scripts/run_emulator.sh --device   -> a USB-connected phone (needed for real Bluetooth)
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$APP_DIR"

if [ "${1:-}" != "--device" ]; then
  if ! adb devices | grep -q '^emulator-'; then
    echo "Starting emulator $AVD_NAME..."
    nohup emulator -avd "$AVD_NAME" -netdelay none -netspeed full >/tmp/hapticglove-emulator.log 2>&1 &
  fi
  adb wait-for-device
  echo "Waiting for Android to boot..."
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
fi

./gradlew installDebug
adb shell am start -n edu.gatech.phr25.hapticglove/.MainActivity
echo "App launched. Flip the 'Demo' switch on in the emulator (it has no real Bluetooth)."
