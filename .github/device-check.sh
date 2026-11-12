#!/usr/bin/env bash
set -euo pipefail
application_id=$("$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging app/build/outputs/apk/debug/app-debug.apk | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[[ -n "$application_id" ]]
collect_screenshots() {
  test_status=$?
  trap - EXIT
  if ! adb pull /sdcard/Android/data/$application_id/files/screenshots screenshots; then
    if [[ $test_status -eq 0 ]]; then test_status=1; fi
  fi
  exit "$test_status"
}
trap collect_screenshots EXIT
./gradlew connectedDebugAndroidTest --stacktrace \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.locale="${1:-en}"
