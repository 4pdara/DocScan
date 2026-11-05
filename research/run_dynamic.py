import json,pathlib,subprocess,time,os
root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
adb=['adb']
def call(args,timeout=30,check=True):
 p=subprocess.run(adb+args,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
 s=p.stdout.decode('utf-8','replace')
 if check and p.returncode:raise RuntimeError((args,p.returncode,s))
 return s
def phase(s):print(json.dumps({'phase':s,'at':time.time()}),flush=True)
def install(args):
 for attempt in range(8):
  try:return call(args,timeout=360)
  except RuntimeError as e:
   if "Can't find service: package" not in str(e):raise
   phase('package_service_restarting');time.sleep(15)
 raise RuntimeError('Package service did not remain available')
phase('waiting_for_boot')
end=time.monotonic()+900
while time.monotonic()<end:
 try:
  boot=call(['shell','-n','getprop','sys.boot_completed'],timeout=8,check=False).strip()=='1'
  if boot:
   print('ready_state',json.dumps({'boot_completed':boot}),flush=True);break
 except subprocess.TimeoutExpired:pass
 time.sleep(5)
else:raise RuntimeError('Android did not complete boot in 900 seconds')
phase('framework_services_available');print('abi',call(['shell','-n','getprop','ro.product.cpu.abilist']).strip(),flush=True)
print('native_bridge',call(['shell','-n','getprop','ro.dalvik.vm.native.bridge']).strip(),flush=True)
print(call(['root']),flush=True);call(['wait-for-device'],timeout=45)
phase('install_probe');print(install(['install','--no-streaming','-r','research/probe/docscan-jni-probe.apk']),flush=True)
print(call(['shell','-n','am','start','-n','org.docscan.engineprobe/.ProbeActivity'],timeout=30),flush=True)
phase('run_probe')
last='';end=time.monotonic()+300
while time.monotonic()<end:
 try:
  s=call(['shell','-n','run-as','org.docscan.engineprobe','cat','files/probe.txt'],timeout=15,check=False)
  if s!=last:
   (root/'research/probe-result.txt').write_text(s);print(s[len(last):],flush=True);last=s
  if 'DONE' in s:break
 except subprocess.TimeoutExpired:pass
 time.sleep(5)
phase('install_camscanner')
print(install(['install-multiple','--no-streaming','-r','research/private/base.apk','research/private/split_config.arm64_v8a.apk','research/private/split_config.en.apk']),flush=True)
for permission in ['android.permission.CAMERA','android.permission.READ_EXTERNAL_STORAGE','android.permission.WRITE_EXTERNAL_STORAGE']:
 print(call(['shell','-n','pm','grant','com.intsig.camscanner',permission],check=False),flush=True)
call(['shell','-n','mkdir','-p','/sdcard/Download'])
print(call(['push','research/fixture.png','/sdcard/Download/DocScan-Research-Input.png']),flush=True)
print(call(['push','tools/frida-server-17.25.2-android-x86_64','/data/local/tmp/frida-server']),flush=True)
call(['shell','-n','chmod','755','/data/local/tmp/frida-server'])
call(['shell','-n','/data/local/tmp/frida-server > /data/local/tmp/frida-server-log.txt 2>&1 &'])
call(['forward','tcp:27042','tcp:27042']);time.sleep(2)
phase('frida_spawn_camscanner')
with (root/'research/frida-camscanner.log').open('w') as log:
 p=subprocess.Popen(['/root/.local/bin/frida','-H','127.0.0.1:27042','-f','com.intsig.camscanner','-l','research/observe_engine.js','-q','-t','600'],stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT)
 print('frida_client_pid',p.pid,flush=True);p.wait(timeout=630)
phase('frida_finished')
