#!/usr/bin/env bash
# Records how the package manager answers the exact published 0.1.8 APK (no set -e: every case must run).
mkdir -p diag; app=com.docscan.rebuild.csnative
attempt() {
  name=$1; shift
  echo "### $name: adb install $*" | tee -a diag/summary.txt
  adb logcat -c
  adb install "$@" > "diag/$name.txt" 2>&1; echo "exit=$?" >> "diag/$name.txt"
  tee -a diag/summary.txt < "diag/$name.txt"
  adb shell dumpsys package "$app" | grep -E "versionName|primaryCpuAbi" | tee -a diag/summary.txt
  adb logcat -d > "diag/$name-logcat.txt"
  grep -E "PackageManager|PackageInstaller|NativeLibrary|INSTALL_" "diag/$name-logcat.txt" | tail -25 >> diag/summary.txt
}
attempt A-clean-0.1.8-arm64 -r --abi arm64-v8a apks/DocScan-0.1.8-universal.apk
adb uninstall "$app"
attempt B-old-0.1.7-arm64 -r --abi arm64-v8a apks/DocScan-0.1.7-universal.apk
adb shell am start -W -n "$app/com.docscan.rebuild.MainActivity"
attempt C-update-0.1.8-arm64 -r --abi arm64-v8a apks/DocScan-0.1.8-universal.apk
adb uninstall "$app"
attempt D-old-0.1.7-x86_64 -r apks/DocScan-0.1.7-universal.apk
attempt E-update-0.1.8-x86_64 -r apks/DocScan-0.1.8-universal.apk
echo '--- summary ---'; cat diag/summary.txt
exit 0
