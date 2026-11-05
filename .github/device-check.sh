#!/usr/bin/env bash
set -euo pipefail
collect_screenshots() {
  test_status=$?
  trap - EXIT
  if ! adb pull /sdcard/Android/data/com.docscan.rebuild/files/screenshots screenshots; then
    if [[ $test_status -eq 0 ]]; then test_status=1; fi
  fi
  exit "$test_status"
}
trap collect_screenshots EXIT
./gradlew connectedDebugAndroidTest --stacktrace \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.locale="${1:-en}"
