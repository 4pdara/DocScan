#!/usr/bin/env bash
set -euo pipefail
mkdir -p book-ui-results
collect() {
  status=$?; trap - EXIT
  adb logcat -d > book-ui-results/logcat.txt || true
  adb exec-out screencap -p > book-ui-results/screen.png || true
  adb root || true; adb wait-for-device
  adb pull /sdcard/Android/data/com.docscan.rebuild.csnative/files/screenshots book-ui-results/screenshots || true
  exit "$status"
}
trap collect EXIT
adb install -r ci-apks/debug/app-debug.apk
adb install -r -t ci-apks/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e locale he -e class com.docscan.rebuild.BookFeaturesTest#cropHasEightHandlesAndDraggingASideMovesItsTwoCorners com.docscan.rebuild.csnative.test/com.docscan.rebuild.LocaleTestRunner | tee book-ui-results/instrumentation.txt
grep -F 'OK (1 test)' book-ui-results/instrumentation.txt
