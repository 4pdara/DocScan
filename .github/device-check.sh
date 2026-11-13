#!/usr/bin/env bash
set -euo pipefail
apk_root='ci-apks'
application_id=$("$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$apk_root/debug/app-debug.apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[[ "$application_id" == 'com.docscan.rebuild.preview.v0_1_8_2' ]]
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$apk_root/debug/app-debug.apk" | grep -F "application-label:'DOCSCAN V0.1.8.2'"
mkdir -p device-results
collect_results() {
  test_status=$?; trap - EXIT
  adb pull "/sdcard/Android/data/$application_id/files/tool-exports" device-results/exports || true
  adb logcat -d > device-results/logcat.txt || true
  adb exec-out screencap -p > device-results/final-screen.png || true
  if ! adb pull "/sdcard/Android/data/$application_id/files/screenshots" screenshots; then
    adb root || true; adb wait-for-device
    if ! adb pull "/sdcard/Android/data/$application_id/files/screenshots" screenshots && [[ $test_status -eq 0 ]]; then test_status=1; fi
  fi
  exit "$test_status"
}
trap collect_results EXIT
# Co-install in a disposable emulator. The old package and a private-data sentinel must survive.
legacy_app='com.docscan.rebuild.csnative'
adb install "${2:?Missing 0.1.8 baseline APK}" | tee device-results/baseline-install.txt
adb shell run-as "$legacy_app" mkdir -p files
adb shell run-as "$legacy_app" touch files/coinstall-preserved
legacy_path=$(adb shell pm path "$legacy_app" | tr -d '\r')
adb install -r "$apk_root/debug/app-debug.apk" | tee device-results/preview-install.txt
adb install -r -t "$apk_root/androidTest/debug/app-debug-androidTest.apk"
adb shell am instrument -w -r -e locale "${1:-en}" \
  "$application_id.test/com.docscan.rebuild.LocaleTestRunner" | tee device-results/instrumentation.txt
python3 - <<'PY'
from pathlib import Path
import re
text=Path('device-results/instrumentation.txt').read_text()
if not re.search(r'OK \(\d+ tests?\)',text) or re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED',text):
    raise SystemExit('The complete Android suite did not finish successfully')
PY
adb shell pm path "$application_id"
[[ "$(adb shell pm path "$legacy_app" | tr -d '\r')" == "$legacy_path" ]]
adb shell run-as "$legacy_app" cat files/coinstall-preserved >/dev/null
echo 'Parallel install and legacy private data verified' | tee device-results/coinstall.txt
