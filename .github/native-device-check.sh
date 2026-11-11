#!/usr/bin/env bash
set -euo pipefail
locale="${1:-en}"
apk_root="${2:-native-apks}"
application_id="${3:-com.docscan.rebuild.csnative.preview}"
mkdir -p native-device-results
collect_results() {
  test_status=$?
  trap - EXIT
  adb logcat -d > native-device-results/logcat.txt || true
  if ! adb pull "/sdcard/Android/data/$application_id/files/screenshots" native-device-results/screenshots; then
    if [[ $test_status -eq 0 ]]; then test_status=1; fi
  fi
  exit "$test_status"
}
trap collect_results EXIT
adb install -r "$apk_root/debug/app-debug.apk"
adb install -r "$apk_root/androidTest/debug/app-debug-androidTest.apk"
adb shell am instrument -w -r -e locale "$locale" \
  "$application_id.test/com.docscan.rebuild.LocaleTestRunner" \
  | tee native-device-results/instrumentation.txt
python3 - <<'PY'
from pathlib import Path
import re
text = Path('native-device-results/instrumentation.txt').read_text()
if not re.search(r'OK \(\d+ tests?\)', text) or re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED', text):
    raise SystemExit('Native UI/integration suite did not finish successfully')
PY
