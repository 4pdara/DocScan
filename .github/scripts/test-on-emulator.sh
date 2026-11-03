#!/usr/bin/env bash
set -euo pipefail

collect_device_reports() {
  mkdir -p app/build/reports/screenshots
  adb pull /sdcard/Pictures/DocScanTest app/build/reports/screenshots/ || true
  adb logcat -d > app/build/reports/screenshots/logcat.txt || true
  adb logcat -d -s DocScanTest:E || true
  adb exec-out screencap -p > app/build/reports/screenshots/last-screen.png || true
}
trap collect_device_reports EXIT
mkdir -p app/build/reports/screenshots

./gradlew connectedDebugAndroidTest --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -W -n io.github.user4pdara.docscan.debug/com.docscan.android.MainActivity
adb shell input keyevent 82
sleep 3
adb shell pidof io.github.user4pdara.docscan.debug
adb exec-out screencap -p > app/build/reports/screenshots/home.png
