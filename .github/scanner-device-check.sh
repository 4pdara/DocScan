#!/usr/bin/env bash
# Installs the exact APK that will be published (no -t, no uninstall) and runs the full instrumentation suite.
set -euo pipefail
locale="${1:-en}"
expected_page="${2:-4096}"
suite="${3:-full}"
forced_abi="${4:-}"
app='com.docscan.rebuild.scanner'
root='release-apks'
out='scanner-device-results'
mkdir -p "$out"
collect() {
  status=$?; trap - EXIT
  adb logcat -d > "$out/logcat.txt" || true
  adb pull "/sdcard/Android/data/$app/files/screenshots" "$out/screenshots" || true
  exit "$status"
}
trap collect EXIT
adb shell getprop ro.build.version.sdk | tee "$out/api.txt"
adb shell getprop ro.product.cpu.abilist | tee "$out/abis.txt"
actual_page=$(adb shell getconf PAGESIZE 2>/dev/null | tr -d '\r' || true)
if ! [[ "$actual_page" =~ ^[0-9]+$ ]]; then
  # Android 8 has no getconf executable. Read the running shell's kernel VMA size.
  actual_page=$(adb shell cat /proc/self/smaps | awk '!found && /^KernelPageSize:/ { print $2 * 1024; found = 1 }' | tr -d '\r')
fi
printf '%s\n' "$actual_page" > "$out/page-size.txt"
[[ "$actual_page" == "$expected_page" ]]
install_args=(-r)
if [[ -n "$forced_abi" ]]; then install_args+=(--abi "$forced_abi"); fi
adb install "${install_args[@]}" "$root/DocScan-0.2.2.apk" | tee "$out/app-install.txt"
grep -q '^Success' "$out/app-install.txt"
adb shell dumpsys package "$app" > "$out/package.txt"
grep -q "versionName=0.2.2" "$out/package.txt"
if [[ -n "$forced_abi" ]]; then grep -q "primaryCpuAbi=$forced_abi" "$out/package.txt"; fi
adb shell am start -W -n "$app/com.docscan.rebuild.MainActivity" | tee "$out/launch.txt"
grep -q 'Status: ok' "$out/launch.txt"
adb install -r -t "$root/tests/DocScan-0.2.2-tests.apk"
args=()
if [[ "$suite" == 'smoke' ]]; then
  # The 19-test set the 0.1.8 release pipeline used, plus the original-filter and detection measurements, on Android 8 and 16 KB images (storage, processing, editing,
  # export, migration and every book tool). The full suite crashed there in camera/navigation UI tests; see docs/RELEASE_0.2.0_HE.md.
  classes=''
  for test in localProcessingWorksWhenTheOptionalArm64EngineIsUnavailable realNativePipelineDetectsCropsAndKeepsFullResolution sourceRecipeAndOrderSurviveDatabaseReopen pdfContainsAllPagesAndLeavesMastersUntouched referenceRecipesRoundTripAndNeverFallBackToAnotherFilter lightenAndEcoKeepReadableStrokesResolutionAndOriginal invalidCropCannotOverwriteSourceOrSavedRecipe detectsDarkAndEqualLuminanceColouredBookCovers galleryAndPdfUseTheSameEditedGeometryAtFullResolution existingVersionOneDatabaseMigratesWithoutChangingItsSourceOrRecipe editorImageExportUsesTheUnsavedRecipeInsteadOfTheMaster editorHasInlineFiltersAndActionsAboveSystemNavigation; do
    classes+="${classes:+,}com.docscan.rebuild.RebuildFlowTest#$test"
  done
  for test in colourOnlyBoundariesRemainEligibleForSharpAutomaticCapture texturedCoversWithGlareAndBrokenCornersKeepTheirOuterBoundary splitPreservesOriginalBytesPerspectiveAndHebrewPageOrder fullBackupRestoresSourcesCurvesOcrTrashAndSharedSplitSources unsafeAndCorruptArchivesCannotChangeTheExistingLibrary dewarpStraightensCurvedLinesWithoutResizingOrChangingSource offlineHebrewAndEnglishRecognitionSurvivesReopenAndEditInvalidatesIt; do
    classes+=",com.docscan.rebuild.BookFeaturesTest#$test"
  done
  classes+=",com.docscan.rebuild.RebuildFlowTest#originalFiltersRemoveShadowsKeepInkColourAndSeparateBoardStrokes"
  classes+=",com.docscan.rebuild.BookFeaturesTest#detectsCoversAtTheFrameEdgeAndThePageOfAnOpenBook"
  classes+=",com.docscan.rebuild.RebuildFlowTest#discardedScanRemovesItsPagesAndUnsharedOriginalsOnly"
  args=(-e class "$classes")
fi
adb shell am instrument -w -r -e locale "$locale" "${args[@]}" "$app.test/com.docscan.rebuild.LocaleTestRunner" | tee "$out/instrumentation.txt"
python3 - "$out/instrumentation.txt" "$suite" <<'PY'
from pathlib import Path
import re, sys
text = Path(sys.argv[1]).read_text()
expected = 22 if sys.argv[2] == 'smoke' else 35
if not re.search(r'^OK \((\d+) tests\)', text, re.M) or re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED', text):
    raise SystemExit('Exact APK instrumentation suite did not pass')
count = int(re.search(r'^OK \((\d+) tests\)', text, re.M).group(1))
if count != expected:
    raise SystemExit(f'Only {count} tests ran')
print(f'{count} tests passed')
PY
