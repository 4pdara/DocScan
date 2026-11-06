#!/usr/bin/env bash
set -euo pipefail
mkdir -p research-original-results
adb shell getprop ro.product.cpu.abilist > research-original-results/abilist.txt
adb root
adb wait-for-device
adb push /tmp/frida-server /data/local/tmp/frida-server
adb shell chmod 755 /data/local/tmp/frida-server
adb shell '/data/local/tmp/frida-server > /data/local/tmp/frida-original.log 2>&1 &'
adb forward tcp:27042 tcp:27042
adb install -r /tmp/CamScanner.apk
for permission in android.permission.CAMERA android.permission.READ_EXTERNAL_STORAGE android.permission.WRITE_EXTERNAL_STORAGE; do
 adb shell pm grant com.intsig.camscanner "$permission" || true
done
python3 - <<'PY'
import struct,zlib
w,h=320,480
pixels=b''.join(b'\0'+b''.join(bytes((25,30,20)) if 30<x<290 and 40<y<440 and y%30<5 else bytes((200,210,190)) for x in range(w)) for y in range(h))
def chunk(t,d):return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
open('/tmp/DocScan-Research-Input.png','wb').write(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b''))
PY
adb push /tmp/DocScan-Research-Input.png /sdcard/Download/DocScan-Research-Input.png
# Fresh, unsigned-in lab guest. No personal scans, account, purchased entitlements or modified APK.
adb shell svc wifi disable
adb shell svc data disable
# Let the application's own loader finish before attaching; compare ordinary startup with spawn.
adb shell am start -W -n com.intsig.camscanner/com.intsig.camscanner.mainmenu.mainactivity.MainActivity > research-original-results/normal-start.txt 2>&1 || true
sleep 20
adb logcat -d -v threadtime > research-original-results/normal-start-logcat.txt
app_pid=$(adb shell pidof com.intsig.camscanner | tr -d '\r' || true)
if [[ -n "$app_pid" ]]; then
 frida -H 127.0.0.1:27042 -p "$app_pid" -l research/observe_engine.js -q -t 100 > research-original-results/frida-java.txt 2>&1 &
 java_pid=$!
 sleep 10
 frida -H 127.0.0.1:27042 -p "$app_pid" --realm=emulated -l research/observe_native.js -q -t 85 > research-original-results/frida-arm.txt 2>&1 &
 arm_pid=$!
 wait "$arm_pid" || true
 wait "$java_pid" || true
else
 echo 'Original process exited during normal startup before Frida attach.' > research-original-results/frida-java.txt
fi
adb exec-out screencap -p > research-original-results/original-state.png
adb logcat -d -v threadtime > research-original-results/logcat.txt
adb pull /data/local/tmp/frida-original.log research-original-results/frida-server.txt || true
adb pull /data/user/0/com.intsig.camscanner/files/docscan-research research-original-results/synthetic-outputs || true
python3 - <<'PY'
from pathlib import Path
import json
p=Path('research-original-results');events=[]
for name in ['frida-java.txt','frida-arm.txt']:
 if not (p/name).exists():continue
 for line in (p/name).read_text().splitlines():
  try: events.append(json.loads(line))
  except ValueError:pass
summary={'apk_version':'7.28.0.2610220000','original_user_reference_version':'7.28.0.2610210000','signed_in':False,'network':'disabled','hooks_ready':any(e.get('event')=='hooks_ready' for e in events),'java_calls':sum(e.get('event')=='java_return' for e in events),'native_calls':sum(e.get('event')=='arm_jni_return' for e in events),'fixture_done':any(e.get('event')=='fixture_done' for e in events),'fixtures':[e for e in events if e.get('event')=='fixture_result']}
(p/'events.json').write_text(json.dumps(events,indent=2));(p/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary))
assert summary['hooks_ready'] and summary['fixture_done'], 'Original CamScanner did not complete the observed fixture path; inspect captured evidence'
PY
