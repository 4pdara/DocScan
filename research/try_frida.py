"""Try the original supplied APK on the lab guest; report failures, never patch checks."""
import pathlib,subprocess,time,json
root=pathlib.Path(__file__).resolve().parents[1]
def adb(args,timeout=30):
 p=subprocess.run(['adb']+args,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
 print(json.dumps({'stage':args[0],'exit_code':p.returncode,'output':p.stdout.decode('utf-8','replace')}),flush=True)
 if p.returncode:raise RuntimeError('adb '+args[0]+' failed')
adb(['root']);adb(['wait-for-device'])
adb(['push',str(root/'tools/frida-server-17.25.2-android-x86_64'),'/data/local/tmp/frida-server'],timeout=90)
adb(['shell','-n','chmod','755','/data/local/tmp/frida-server'])
adb(['shell','-n','/data/local/tmp/frida-server > /data/local/tmp/frida-server-log.txt 2>&1 &'])
adb(['forward','tcp:27042','tcp:27042']);time.sleep(2)
with (root/'research/frida-processes.log').open('w') as log:
 p=subprocess.run(['/root/.local/bin/frida-ps','-H','127.0.0.1:27042'],stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,timeout=30)
 print(json.dumps({'stage':'frida_processes','exit_code':p.returncode}),flush=True)
adb(['install-multiple','--no-streaming','-r',str(root/'research/private/base.apk'),str(root/'research/private/split_config.arm64_v8a.apk'),str(root/'research/private/split_config.en.apk')],timeout=150)
with (root/'research/frida-camscanner.log').open('w') as log:
 p=subprocess.run(['/root/.local/bin/frida','-H','127.0.0.1:27042','-f','com.intsig.camscanner','-l',str(root/'research/observe_engine.js'),'-q','-t','120'],stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,timeout=150)
 print(json.dumps({'stage':'frida_camscanner','exit_code':p.returncode}),flush=True)
