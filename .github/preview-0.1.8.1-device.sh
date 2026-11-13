#!/usr/bin/env bash
set -euo pipefail
locale="${1:-en}"
suite="${2:-full}"
expected_page="${3:-4096}"
legacy_case="${4:-none}"
app='com.docscan.rebuild.csnative.v0_1_8_1'
legacy_app='com.docscan.rebuild.csnative'
root='verified-apks'
mkdir -p preview-device-results
collect() {
  status=$?; trap - EXIT
  timeout 15s adb logcat -d > preview-device-results/logcat.txt || true
  timeout 15s adb root || true; timeout 20s adb wait-for-device || true
  timeout 30s adb pull "/sdcard/Android/data/$app/files/screenshots" preview-device-results/screenshots || true
  exit "$status"
}
trap collect EXIT
adb shell getprop ro.build.version.sdk > preview-device-results/api.txt
adb shell getprop ro.product.cpu.abilist > preview-device-results/abis.txt
actual_page=$(adb shell getconf PAGESIZE 2>/dev/null | tr -d '\r' || true)
if ! [[ "$actual_page" =~ ^[0-9]+$ ]]; then
  # Android 8 has no getconf executable. Read the running shell's kernel VMA size.
  actual_page=$(adb shell cat /proc/self/smaps | awk '!found && /^KernelPageSize:/ { print $2 * 1024; found = 1 }' | tr -d '\r')
fi
printf '%s\n' "$actual_page" > preview-device-results/page-size.txt
[[ "$actual_page" == "$expected_page" ]]
adb install -r legacy-apk/DocScan-0.1.8-universal.apk | tee preview-device-results/legacy-install.txt
adb shell "run-as $legacy_app sh -c 'mkdir -p files/masters; echo preserved-original-bytes > files/masters/installation-check.source'"
adb shell "run-as $legacy_app cat files/masters/installation-check.source" > preview-device-results/before-install.txt
legacy_path=$(adb shell pm path "$legacy_app" | tr -d '\r')
adb install "$root/app/DocScan-0.1.8.1-universal.apk" | tee preview-device-results/new-install.txt
adb shell "run-as $legacy_app cat files/masters/installation-check.source" > preview-device-results/after-install.txt
cmp preview-device-results/before-install.txt preview-device-results/after-install.txt
[[ "$(adb shell pm path "$legacy_app" | tr -d '\r')" == "$legacy_path" ]]
adb install -r -t "$root/tests/DocScan-0.1.8.1-tests.apk"
args=()
if [[ "$suite" == 'smoke' ]]; then
  classes=''
  for test in localProcessingWorksWhenTheOptionalArm64EngineIsUnavailable realNativePipelineDetectsCropsAndKeepsFullResolution sourceRecipeAndOrderSurviveDatabaseReopen pdfContainsAllPagesAndLeavesMastersUntouched referenceRecipesRoundTripAndNeverFallBackToAnotherFilter lightenAndEcoKeepReadableStrokesResolutionAndOriginal invalidCropCannotOverwriteSourceOrSavedRecipe detectsDarkAndEqualLuminanceColouredBookCovers galleryAndPdfUseTheSameEditedGeometryAtFullResolution existingVersionOneDatabaseMigratesWithoutChangingItsSourceOrRecipe editorImageExportUsesTheUnsavedRecipeInsteadOfTheMaster editorHasInlineFiltersAndActionsAboveSystemNavigation; do
    classes+="${classes:+,}com.docscan.rebuild.RebuildFlowTest#$test"
  done
  for test in colourOnlyBoundariesRemainEligibleForSharpAutomaticCapture texturedCoversWithGlareAndBrokenCornersKeepTheirOuterBoundary splitPreservesOriginalBytesPerspectiveAndHebrewPageOrder fullBackupRestoresSourcesCurvesOcrTrashAndSharedSplitSources unsafeAndCorruptArchivesCannotChangeTheExistingLibrary dewarpStraightensCurvedLinesWithoutResizingOrChangingSource offlineHebrewAndEnglishRecognitionSurvivesReopenAndEditInvalidatesIt; do
    classes+=",com.docscan.rebuild.BookFeaturesTest#$test"
  done
  for test in batchCancelResumeKeepsManualCorrectionsAndFullOriginals literalSearchTracksCurrentEditsAndExportRejectsMissingPages; do
    classes+=",com.docscan.rebuild.DocumentOcrTest#$test"
  done
  args=(-e class "$classes")
fi
timeout 15m adb shell am instrument -w -r -e locale "$locale" "${args[@]}" "$app.test/com.docscan.rebuild.LocaleTestRunner" | tee preview-device-results/instrumentation.txt
python3 - "$suite" <<'PY'
from pathlib import Path
import re,sys
text=Path('preview-device-results/instrumentation.txt').read_text()
expected=21 if sys.argv[1]=='smoke' else 34
if f'OK ({expected} tests)' not in text or re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED',text):
    raise SystemExit('Exact signed APK installation suite did not pass')
PY

echo "Signed APK and preserved previous installation verified" > preview-device-results/verified.txt
