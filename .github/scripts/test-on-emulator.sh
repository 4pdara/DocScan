#!/usr/bin/env bash
set -euo pipefail

collect_device_reports() {
  mkdir -p app/build/reports/screenshots
  adb pull /sdcard/Pictures/DocScanTest app/build/reports/screenshots/ || true
  java -Djava.awt.headless=true .github/scripts/ReviewScreenshots.java app/build/reports/screenshots/ || true
  python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
for path in Path("app/build/outputs/androidTest-results").rglob("*.xml"):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        for failure in case.findall("failure") + case.findall("error"):
            print("DOCSCAN_TEST_FAILURE:", case.get("classname"), case.get("name"))
            print((failure.text or failure.get("message", ""))[:12000])
PY
  adb logcat -d > app/build/reports/screenshots/logcat.txt || true
  adb logcat -d -s DocScanTest:E || true
  adb exec-out screencap -p > app/build/reports/screenshots/last-screen.png || true
}
trap collect_device_reports EXIT
mkdir -p app/build/reports/screenshots

# Legacy storage permissions must be granted before the test app process starts,
# so Android mounts its external storage with the corresponding access.
device_api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [[ "$device_api" =~ ^[0-9]+$ ]] && (( device_api <= 28 )); then
  ./gradlew assembleDebug assembleDebugAndroidTest --console=plain
  adb install -r -g app/build/outputs/apk/debug/app-debug.apk
fi

test_args=()
if [[ -n "${DOCSCAN_DEVICE_TEST_CLASS:-}" ]]; then
  test_args+=("-Pandroid.testInstrumentationRunnerArguments.class=$DOCSCAN_DEVICE_TEST_CLASS")
fi
./gradlew connectedDebugAndroidTest --console=plain "${test_args[@]}"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -W -n io.github.user4pdara.docscan.debug/com.docscan.android.MainActivity
adb shell input keyevent 82
sleep 3
adb shell pidof io.github.user4pdara.docscan.debug
adb exec-out screencap -p > app/build/reports/screenshots/home.png
