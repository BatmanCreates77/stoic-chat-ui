#!/usr/bin/env bash
# Clean build, unit tests, release APK, then prove the file is a valid signed package.
set -euo pipefail
cd "$(dirname "$0")"
./gradlew clean :app:testDebugUnitTest :app:assembleRelease --no-daemon -q
APK=app/build/outputs/apk/release/app-release.apk
unzip -tq "$APK"
BT="${ANDROID_HOME:-/opt/android-sdk}/build-tools/35.0.0"
"$BT/apksigner" verify --verbose "$APK" | head -4
"$BT/aapt2" dump badging "$APK" | grep -E "^package|sdkVersion|targetSdk"
echo "OK: $APK ($(stat -c %s "$APK") bytes)"
