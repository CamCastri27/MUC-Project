# Sourced by the other scripts. Points at the Homebrew-installed JDK 17 and Android SDK.
# Override by exporting JAVA_HOME / ANDROID_HOME yourself (e.g. if you use Android Studio's SDK).
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"17'; then
  export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -d "$HOME/Library/Android/sdk/platform-tools" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"          # Android Studio's default
  else
    export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
  fi
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
AVD_NAME="HapticGlove_Pixel"
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
