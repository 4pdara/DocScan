#!/usr/bin/env bash
set -euo pipefail
locale="${1:-en}"
suite="${2:-full}"
expected_page="${3:-4096}"
legacy_case="${4:-none}"
app='com.docscan.rebuild.csnative'
root='verified-apks'
mkdir -p universal-device-results
collect() {
  status=$?; trap - EXIT
  adb logcat -d > universal-device-results/logcat.txt || true
  adb root || true; adb wait-for-device
  adb pull "/sdcard/Android/data/$app/files/screenshots" universal-device-results/screenshots || true
  exit "$status"
}
trap collect EXIT
adb shell getprop ro.build.version.sdk > universal-device-results/api.txt
adb shell getprop ro.product.cpu.abilist > universal-device-results/abis.txt
actual_page=$(adb shell getconf PAGESIZE 2>/dev/null | tr -d '\r' || true)
if ! [[ "$actual_page" =~ ^[0-9]+$ ]]; then
  # Android 8 has no getconf executable. Read the running shell's kernel VMA size.
  actual_page=$(adb shell cat /proc/self/smaps | awk '!found && /^KernelPageSize:/ { print $2 * 1024; found = 1 }' | tr -d '\r')
fi
printf '%s\n' "$actual_page" > universal-device-results/page-size.txt
[[ "$actual_page" == "$expected_page" ]]
if [[ "$legacy_case" == 'upgrade' ]]; then
  adb install -r legacy-apk/DocScan-0.1.7-universal.apk | tee universal-device-results/old-arm64-install.txt
  adb shell am start -W -n "$app/com.docscan.rebuild.MainActivity"
  adb shell "run-as $app sh -c 'mkdir -p files/masters; echo preserved-original-bytes > files/masters/installation-check.source'"
  adb shell "run-as $app cat files/masters/installation-check.source" > universal-device-results/before-update.txt
fi
# This is the same certificate, bytes and filename delivered to users; no -t or uninstall.
adb install -r "$root/app/DocScan-0.1.8-universal.apk" | tee universal-device-results/universal-install.txt
if [[ "$legacy_case" == 'upgrade' ]]; then
  adb shell "run-as $app cat files/masters/installation-check.source" > universal-device-results/after-update.txt
  cmp universal-device-results/before-update.txt universal-device-results/after-update.txt
fi
adb install -r -t "$root/tests/DocScan-0.1.8-tests.apk"
args=()
if [[ "$suite" == 'smoke' ]]; then
  classes=''
  for test in localProcessingWorksWhenTheOptionalArm64EngineIsUnavailable realNativePipelineDetectsCropsAndKeepsFullResolution sourceRecipeAndOrderSurviveDatabaseReopen pdfContainsAllPagesAndLeavesMastersUntouched referenceRecipesRoundTripAndNeverFallBackToAnotherFilter lightenAndEcoKeepReadableStrokesResolutionAndOriginal invalidCropCannotOverwriteSourceOrSavedRecipe detectsDarkAndEqualLuminanceColouredBookCovers galleryAndPdfUseTheSameEditedGeometryAtFullResolution existingVersionOneDatabaseMigratesWithoutChangingItsSourceOrRecipe editorImageExportUsesTheUnsavedRecipeInsteadOfTheMaster editorHasInlineFiltersAndActionsAboveSystemNavigation; do
    classes+="${classes:+,}com.docscan.rebuild.RebuildFlowTest#$test"
  done
  for test in colourOnlyBoundariesRemainEligibleForSharpAutomaticCapture texturedCoversWithGlareAndBrokenCornersKeepTheirOuterBoundary splitPreservesOriginalBytesPerspectiveAndHebrewPageOrder fullBackupRestoresSourcesCurvesOcrTrashAndSharedSplitSources unsafeAndCorruptArchivesCannotChangeTheExistingLibrary dewarpStraightensCurvedLinesWithoutResizingOrChangingSource offlineHebrewAndEnglishRecognitionSurvivesReopenAndEditInvalidatesIt; do
    classes+=",com.docscan.rebuild.BookFeaturesTest#$test"
  done
  args=(-e class "$classes")
fi
adb shell am instrument -w -r -e locale "$locale" "${args[@]}" "$app.test/com.docscan.rebuild.LocaleTestRunner" | tee universal-device-results/instrumentation.txt
python3 - "$suite" <<'PY'
from pathlib import Path
import re,sys
text=Path('universal-device-results/instrumentation.txt').read_text()
expected=19 if sys.argv[1]=='smoke' else 31
if f'OK ({expected} tests)' not in text or re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED',text):
    raise SystemExit('Exact signed APK installation suite did not pass')
PY
