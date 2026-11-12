#!/usr/bin/env bash
set -euo pipefail
application_id=$("$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging app/build/outputs/apk/debug/app-debug.apk | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[[ "$application_id" == 'com.docscan.rebuild.preview.v0_1_8_1' ]]
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging app/build/outputs/apk/debug/app-debug.apk | grep -F "application-label:'DOCSCAN V0.1.8.1'"
# Co-install in a disposable emulator. The old package and a private-data sentinel must survive.
legacy_app='com.docscan.rebuild.csnative'
adb install "${2:?Missing 0.1.8 baseline APK}"
adb shell run-as "$legacy_app" mkdir -p files
adb shell run-as "$legacy_app" touch files/coinstall-preserved
legacy_path=$(adb shell pm path "$legacy_app" | tr -d '\r')
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

adb shell pm path "$application_id"
[[ "$(adb shell pm path "$legacy_app" | tr -d '\r')" == "$legacy_path" ]]
adb shell run-as "$legacy_app" test -f files/coinstall-preserved
