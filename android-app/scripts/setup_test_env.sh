#!/usr/bin/env bash
# One-time setup: installs the Android SDK pieces + creates an emulator.
# Usage: ./scripts/setup_test_env.sh
set -euo pipefail
source "$(dirname "$0")/env.sh"

if ! command -v sdkmanager >/dev/null; then
  echo "Installing JDK 17 and Android command-line tools with Homebrew..."
  brew install openjdk@17
  brew install --cask android-commandlinetools
  source "$(dirname "$0")/env.sh"
fi

ARCH="$(uname -m)"; [ "$ARCH" = "arm64" ] && ABI="arm64-v8a" || ABI="x86_64"
IMAGE="system-images;android-35;google_apis;$ABI"

yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "emulator" "platforms;android-35" "build-tools;35.0.0" "$IMAGE"

if ! avdmanager list avd -c | grep -qx "$AVD_NAME"; then
  echo "no" | avdmanager create avd -n "$AVD_NAME" -k "$IMAGE" -d pixel_7
fi

echo "sdk.dir=$ANDROID_HOME" > "$APP_DIR/local.properties"
echo
echo "Done. Now run: ./scripts/run_emulator.sh"
