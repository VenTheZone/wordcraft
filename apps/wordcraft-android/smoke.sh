#!/usr/bin/env bash
# Emulator check for the Android app: boots the tablet emulator if no device is connected, builds
# and installs the debug APK, opens WordCraft on the sample document, optionally taps the page and
# types, and saves a screenshot (path printed). Usage: smoke.sh [out.png] [text to type]
#
# The typed text may only hold letters, digits, spaces and . _ , : @ = + / - because `adb shell
# input text` passes it through the device shell.
#
# The AVD `wordcraft-tablet` (1600x2560, 320 dpi, API 34 google_apis x86_64) was made by hand
# because avdmanager refused to create it:
#   1. sdkmanager "system-images;android-34;google_apis;x86_64"
#   2. write ~/.android/avd/wordcraft-tablet.ini:
#        avd.ini.encoding=UTF-8
#        path=$HOME/.android/avd/wordcraft-tablet.avd
#        path.rel=avd/wordcraft-tablet.avd
#        target=android-34
#   3. write ~/.android/avd/wordcraft-tablet.avd/config.ini:
#        AvdId=wordcraft-tablet
#        avd.ini.displayname=wordcraft-tablet
#        image.sysdir.1=system-images/android-34/google_apis/x86_64/
#        abi.type=x86_64
#        hw.cpu.arch=x86_64
#        hw.cpu.ncore=4
#        hw.ramSize=3072
#        hw.lcd.width=1600
#        hw.lcd.height=2560
#        hw.lcd.density=320
#        hw.keyboard=yes
#        hw.gpu.enabled=yes
#        hw.gpu.mode=swiftshader_indirect
#        disk.dataPartition.size=6442450944
#        tag.id=google_apis
#        tag.display=Google APIs
#        PlayStore.enabled=false
#        hw.device.name=pixel_tablet
#        hw.device.manufacturer=Google
# The emulator then creates the rest of the AVD on first boot.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
OUT="${1:-/tmp/wordcraft-android.png}"
if [ $# -ge 2 ]; then
  case "$2" in
    *[!A-Za-z0-9\ ._,:@=+/-]*) echo "smoke.sh: text may only contain letters, digits, spaces and . _ , : @ = + / -" >&2; exit 2 ;;
  esac
fi
if ! "$ADB" get-state >/dev/null 2>&1; then
  "$SDK/emulator/emulator" -avd "${AVD:-wordcraft-tablet}" -gpu swangle_indirect -no-window -no-audio -no-boot-anim -no-snapshot-save >/tmp/wordcraft-emulator.log 2>&1 &
  timeout 600 "$ADB" wait-for-device || { echo "smoke.sh: no emulator device after 600 s (see /tmp/wordcraft-emulator.log)" >&2; exit 1; }
  deadline=$((SECONDS + 600))
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    if [ "$SECONDS" -ge "$deadline" ]; then echo "smoke.sh: emulator did not finish booting in 600 s (see /tmp/wordcraft-emulator.log)" >&2; exit 1; fi
    sleep 2
  done
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
