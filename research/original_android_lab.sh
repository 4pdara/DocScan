#!/usr/bin/env bash
set -euo pipefail
mkdir -p research-original-results
collect_evidence() {
 status=$?
 trap - EXIT
 timeout 20s adb exec-out screencap -p > research-original-results/original-state.png || true
 timeout 20s adb logcat -d -v threadtime > research-original-results/logcat.txt || true
 timeout 20s adb pull /data/local/tmp/frida-original.log research-original-results/frida-server.txt || true
 timeout 20s adb pull /data/user/0/com.intsig.camscanner/files/docscan-research research-original-results/synthetic-outputs || true
 exit "$status"
}
trap collect_evidence EXIT
# ADB may briefly go offline immediately after boot or adbd restarts as root.
# Retry initialization only; never alter the guest's protection or app checks.
retry_adb() {
 for attempt in 1 2 3; do
  if timeout 30s adb "$@"; then return 0; fi
  timeout 30s adb wait-for-device || true
  sleep 2
 done
 return 1
}
retry_adb wait-for-device
retry_adb root
retry_adb wait-for-device
retry_adb shell id -u > research-original-results/adb-user.txt
[[ "$(tr -d '\r' < research-original-results/adb-user.txt)" == 0 ]]
retry_adb shell getprop ro.boot.qemu > research-original-results/emulator-property.txt
retry_adb shell getprop ro.dalvik.vm.native.bridge > research-original-results/native-bridge.txt
retry_adb shell getconf _NPROCESSORS_ONLN > research-original-results/cpu-count.txt
retry_adb shell getprop ro.product.cpu.abilist > research-original-results/abilist.txt
timeout 60s adb push /tmp/frida-server /data/local/tmp/frida-server
timeout 15s adb shell chmod 755 /data/local/tmp/frida-server
timeout 15s adb shell '/data/local/tmp/frida-server > /data/local/tmp/frida-original.log 2>&1 &'
timeout 15s adb forward tcp:27042 tcp:27042
timeout 120s adb install --no-streaming -r /tmp/CamScanner.apk
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
# Record ordinary launcher startup before using read-only Frida observers.
timeout 10s adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.intsig.camscanner > research-original-results/launcher.txt
launcher=$(tail -1 research-original-results/launcher.txt | tr -d '\r')
[[ "$launcher" == com.intsig.camscanner/* ]]
adb logcat -c
timeout 40s adb shell am start -W -n "$launcher" > research-original-results/normal-start.txt 2>&1 || true
sleep 8
timeout 20s adb logcat -d -v threadtime > research-original-results/normal-start-logcat.txt
adb shell am force-stop com.intsig.camscanner
# Synthetic input is placed in the fresh app sandbox, avoiding scoped-storage denial.
adb shell mkdir -p /data/user/0/com.intsig.camscanner/files
adb push /tmp/DocScan-Research-Input.png /data/user/0/com.intsig.camscanner/files/docscan-research-input.png
owner=$(adb shell stat -c '%u:%g' /data/user/0/com.intsig.camscanner | tr -d '\r')
[[ "$owner" =~ ^[0-9]+:[0-9]+$ ]]
adb shell chown "$owner" /data/user/0/com.intsig.camscanner/files /data/user/0/com.intsig.camscanner/files/docscan-research-input.png
adb shell restorecon -RF /data/user/0/com.intsig.camscanner/files
timeout 190s python3 research/observe_original_startup.py > research-original-results/frida-startup.txt 2>&1 || true
python3 - <<'PY'
from pathlib import Path
import json
p=Path('research-original-results');events=[]
for name in ['frida-startup.txt']:
 if not (p/name).exists():continue
 for line in (p/name).read_text().splitlines():
  try: events.append(json.loads(line))
  except ValueError:pass
summary={'observation':'unmodified-official-reference','apk_version':'7.28.0.2610220000','original_user_reference_version':'7.28.0.2610210000','signed_in':False,'network':'disabled','android_api':int(__import__('subprocess').check_output(['adb','shell','getprop','ro.build.version.sdk'],text=True).strip()),'hooks_ready':any(e.get('event')=='hooks_ready' for e in events),'java_calls':sum(e.get('event')=='java_return' for e in events),'native_calls':sum(e.get('event')=='arm_jni_return' for e in events),'fixture_done':any(e.get('event')=='fixture_done' for e in events),'fixtures':[e for e in events if e.get('event')=='fixture_result']}
(p/'events.json').write_text(json.dumps(events,indent=2));(p/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary))
assert summary['hooks_ready'] and summary['fixture_done'] and len(summary['fixtures']) == 5, 'Original CamScanner did not complete the observed fixture path; inspect captured evidence'
PY
