#!/usr/bin/env bash
set -euo pipefail
mkdir -p research-matrix
adb root
adb wait-for-device
adb shell getprop ro.build.version.sdk > research-matrix/api.txt
adb shell getprop ro.product.cpu.abilist > research-matrix/abis.txt
adb shell getprop ro.dalvik.vm.native.bridge > research-matrix/native-bridge.txt
timeout 90s adb install -r app/build/outputs/apk/debug/app-debug.apk
timeout 90s adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
component='com.docscan.rebuild.csnative.test/com.docscan.rebuild.LocaleTestRunner'
test_class='com.docscan.rebuild.RebuildFlowTest#referenceNativeIsolatedMatrix'
run_case() {
 local boundary=$1 width=$2 treatments=$3 name=$4 fixture=${5:-gradient} shadow_type=${6:-} classify=${7:-true}
 local extras=()
 if [[ -n "$shadow_type" ]]; then extras=(-e researchShadowType "$shadow_type"); fi
 timeout 15s adb shell am force-stop com.docscan.rebuild.csnative || true
 adb logcat -c
 timeout 120s adb shell am instrument -w -r -e class "$test_class" -e researchTreatments "$treatments" -e researchBoundary "$boundary" -e researchWidth "$width" -e researchFixture "$fixture" -e researchClassify "$classify" "${extras[@]}" "$component" > "research-matrix/$name.instrumentation.txt" 2>&1 || true
 timeout 20s adb logcat -d -v threadtime > "research-matrix/$name.logcat.txt" || true
 echo "CASE $name"; tail -4 "research-matrix/$name.instrumentation.txt"
}
for boundary in direct pipeline; do
 for treatment in PAPER NATIVE_MAGIC_COLOR NATIVE_NO_SHADOW NATIVE_INVERT NATIVE_PRO_LOCAL NATIVE_BOARD NATIVE_BOARD_PAPER NATIVE_INVERT_BOARD; do
  run_case "$boundary" 96 "$treatment" "$boundary-96-$treatment"
 done
 run_case "$boundary" 320 NATIVE_NO_SHADOW "$boundary-320-NATIVE_NO_SHADOW"
 run_case "$boundary" 96 NATIVE_NO_SHADOW "$boundary-flat-noshadow" flat
 for shadow in 2 3; do run_case "$boundary" 96 NATIVE_NO_SHADOW "$boundary-flat-shadow$shadow" flat "$shadow"; done
 run_case "$boundary" 96 NATIVE_NO_SHADOW "$boundary-legacy" legacy
 run_case "$boundary" 96 NATIVE_NO_SHADOW "$boundary-legacy-no-classifier" legacy '' false
 run_case "$boundary" 96 NATIVE_NO_SHADOW "$boundary-gradient-no-classifier" gradient '' false
 run_case "$boundary" 320 NATIVE_BOARD,NATIVE_BOARD_PAPER,NATIVE_INVERT_BOARD "$boundary-board" board
 run_case "$boundary" 96 PAPER,NATIVE_MAGIC_COLOR,NATIVE_NO_SHADOW,NATIVE_INVERT,NATIVE_PRO_LOCAL,NATIVE_BOARD,NATIVE_BOARD_PAPER,NATIVE_INVERT_BOARD "$boundary-sequence"
done
run_case pipeline 1280 NATIVE_NO_SHADOW full-resolution-noshadow
# Attach both Java and ARM observers to a clean, direct JNI invocation.
timeout 60s adb push /tmp/frida-server /data/local/tmp/frida-server
adb shell chmod 755 /data/local/tmp/frida-server
timeout 15s adb shell '/data/local/tmp/frida-server > /data/local/tmp/frida-matrix.log 2>&1 &'
adb forward tcp:27042 tcp:27042
adb shell am force-stop com.docscan.rebuild.csnative
adb logcat -c
timeout 140s adb shell am instrument -w -r -e class "$test_class" -e researchTreatments NATIVE_NO_SHADOW -e researchBoundary direct -e researchWidth 320 -e researchDelayMs 30000 "$component" > research-matrix/frida.instrumentation.txt 2>&1 &
instrument_pid=$!
app_pid=''
for attempt in $(seq 1 30); do
 app_pid=$(timeout 5s adb shell pidof com.docscan.rebuild.csnative | tr -d '\r' || true)
 if [[ -n "$app_pid" ]]; then break; fi
 sleep 1
done
if [[ -n "$app_pid" ]]; then
 timeout 80s frida -H 127.0.0.1:27042 -p "$app_pid" -l research/observe_java.js -q -t 60 > research-matrix/frida-java.txt 2>&1 &
 java_pid=$!
 sleep 7
 timeout 80s frida -H 127.0.0.1:27042 -p "$app_pid" --realm=emulated -l research/observe_native.js -q -t 55 > research-matrix/frida-arm.txt 2>&1 &
 arm_pid=$!
 wait "$instrument_pid" || true
 wait "$java_pid" || true
 wait "$arm_pid" || true
fi
timeout 20s adb logcat -d -v threadtime > research-matrix/frida.logcat.txt || true
timeout 60s adb pull /sdcard/Android/data/com.docscan.rebuild.csnative/files/filter-probe research-matrix/images || true
python3 - <<'PY'
from pathlib import Path
import json
root=Path('research-matrix');cases=[]
for log in root.glob('*.logcat.txt'):
 for line in log.read_text(errors='replace').splitlines():
  if 'DocScanFilterProbe' not in line or '{' not in line:continue
  try: row=json.loads(line[line.index('{'):])
  except ValueError:continue
  row['case_file']=log.name;cases.append(row)
(root/'events.json').write_text(json.dumps(cases,indent=2))
results=[r for r in cases if r.get('event')=='case_result']
(root/'summary.json').write_text(json.dumps(results,indent=2));print(json.dumps(results,indent=2))
assert results, 'No Native cases executed'
assert all(r.get('passed') and r.get('source_preserved') for r in results), 'Some Native matrix cases failed; inspect saved results'
PY
