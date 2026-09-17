#!/bin/bash
set -e
cd "$(dirname "$0")"
export JAVA_HOME="$(pwd)/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$(pwd)/android-sdk"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root="$SDK" "platforms;android-33" "build-tools;34.0.0"
echo "SDK_INSTALL_DONE"
ls "$SDK/platforms" "$SDK/build-tools"
