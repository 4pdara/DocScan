#!/usr/bin/env bash
set -euo pipefail
trap 'adb pull /sdcard/Android/data/com.docscan.rebuild/files/screenshots screenshots || true' EXIT
./gradlew connectedDebugAndroidTest --stacktrace -Pandroid.testInstrumentationRunnerArguments.locale="${1:-en}"
