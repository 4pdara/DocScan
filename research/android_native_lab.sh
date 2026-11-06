#!/usr/bin/env bash
set -euo pipefail
mkdir -p research-results
adb shell getprop ro.product.cpu.abilist | tee research-results/abilist.txt
adb shell getprop ro.dalvik.vm.native.bridge | tee research-results/native-bridge.txt
adb root
adb wait-for-device
adb push /tmp/frida-server /data/local/tmp/frida-server
adb shell chmod 755 /data/local/tmp/frida-server
adb shell '/data/local/tmp/frida-server > /data/local/tmp/frida-server.log 2>&1 &'
adb forward tcp:27042 tcp:27042
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.docscan.rebuild.RebuildFlowTest#referenceNativeSmokePreservesDimensionsAlphaAndSource -e researchDelayMs 30000 com.docscan.rebuild.csnative.test/com.docscan.rebuild.LocaleTestRunner > research-results/instrumentation.txt 2>&1 &
instrument_pid=$!
app_pid=''
for attempt in $(seq 1 60); do
 app_pid=$(adb shell pidof com.docscan.rebuild.csnative | tr -d '\r' || true)
 if [[ -n "$app_pid" ]]; then break; fi
 sleep 1
done
if [[ -z "$app_pid" ]]; then cat research-results/instrumentation.txt; exit 1; fi
# Host realm: ART/Java. Emulated realm: ARM64 JNI. Observe only, without replacing returns.
frida -H 127.0.0.1:27042 -p "$app_pid" -l research/observe_java.js -q -t 65 > research-results/frida-java.txt 2>&1 &
java_pid=$!
# OpenCV loaded by the delayed smoke before trying to attach to the ARM realm.
sleep 8
frida -H 127.0.0.1:27042 -p "$app_pid" --realm=emulated -l research/observe_native.js -q -t 55 > research-results/frida-arm.txt 2>&1 &
arm_pid=$!
wait "$instrument_pid" || true
wait "$java_pid" || true
wait "$arm_pid" || true
adb logcat -d -v threadtime > research-results/logcat.txt
adb pull /data/local/tmp/frida-server.log research-results/frida-server.txt || true
cat research-results/instrumentation.txt
python3 - <<'PY'
from pathlib import Path
import json
p=Path('research-results')
text=(p/'instrumentation.txt').read_text()
assert 'OK (1 test)' in text and 'FAILURES' not in text, 'Native Android smoke did not pass'
for name,event in [('frida-java.txt','java_return'),('frida-arm.txt','arm_jni_return')]:
 rows=[]
 for line in (p/name).read_text().splitlines():
  try: rows.append(json.loads(line))
  except ValueError: pass
 (p/(name+'.json')).write_text(json.dumps(rows,indent=2))
 assert any(r.get('event')==event for r in rows), 'No observed events in '+name
PY
