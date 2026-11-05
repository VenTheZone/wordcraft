#!/usr/bin/env bash
# Emulator check for the Android app: boots the tablet emulator if no device is connected, builds
# and installs the debug APK, opens WordCraft on the sample document, optionally taps the page and
# types, and saves a screenshot (path printed). Usage: smoke.sh [out.png] [text to type]
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
OUT="${1:-/tmp/wordcraft-android.png}"
if ! "$ADB" get-state >/dev/null 2>&1; then
  "$SDK/emulator/emulator" -avd "${AVD:-wordcraft-tablet}" -no-window -no-audio -no-boot-anim -no-snapshot-save >/tmp/wordcraft-emulator.log 2>&1 &
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
fi
./gradlew -q assembleDebug -Pabis=x86_64
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" shell am force-stop ai.storyteller.wordcraft
"$ADB" shell am start -n ai.storyteller.wordcraft/.MainActivity --ez sample true >/dev/null
sleep 10  # a cold emulator needs a few seconds to draw the first frame
if [ $# -ge 2 ]; then
  "$ADB" shell input tap 400 500; sleep 1
  "$ADB" shell input text "${2// /%s}"; sleep 2
fi
"$ADB" exec-out screencap -p > "$OUT"
echo "$OUT"
