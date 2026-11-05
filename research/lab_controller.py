"""Keep emulator, adb and Frida in one execution environment; accept file requests."""
import concurrent.futures,json,os,pathlib,signal,subprocess,time,sys,threading,tempfile,shutil

root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
queue=root/'research/lab-queue';queue.mkdir(exist_ok=True)
(queue/'STOP').unlink(missing_ok=True)
existing_requests={path.name for path in queue.glob('*.request.json')}
env=os.environ.copy();env['ANDROID_SDK_ROOT']=str(root/'runtime/sdk');env['ANDROID_AVD_HOME']=str(root/'runtime/avd')
env['LD_LIBRARY_PATH']=':'.join(str(root/p) for p in ['runtime/hostlibs/usr/lib/x86_64-linux-gnu','runtime/jdk/usr/lib/x86_64-linux-gnu'])
env['PATH']=str(root/'runtime/sdk/platform-tools')+':/root/.local/bin:'+env['PATH']
use_x86='--arm' not in sys.argv
emulator=root/('runtime/sdk/emulator/emulator' if use_x86 else 'runtime/emu32/emulator/emulator')
name='csx86' if use_x86 else 'csprobe'
image='android-30/google_apis/x86_64' if use_x86 else 'android-27/google_apis/arm64-v8a'
local_avds=pathlib.Path(tempfile.mkdtemp(prefix='docscan-avd-'))
original=root/'runtime/avd'/(name+'.avd');local=local_avds/(name+'.avd');local.mkdir()
config=(original/'config.ini').read_text().replace('hw.cpu.ncore=4','hw.cpu.ncore=1')
(local/'config.ini').write_text(config)
for p in original.iterdir():
 if p.is_file() and (p.name.endswith('.img') or p.name.endswith('.qcow2')):
  subprocess.run(['cp','--sparse=always',str(p),str(local/p.name)],check=True)
(local_avds/(name+'.ini')).write_text('avd.ini.encoding=UTF-8\npath='+str(local)+'\ntarget=android-30\n')
env['ANDROID_AVD_HOME']=str(local_avds)
(root/'research/lab-meta.json').write_text(json.dumps({'local_avd':str(local),'name':name}))
log=(root/'runtime/lab-controller-emulator.log').open('w')
emu_args=[str(emulator),'-avd',name,'-sysdir',str(root/'runtime/sdk/system-images'/image),'-accel','off','-no-window','-no-audio','-no-boot-anim','-no-snapshot','-gpu','swiftshader_indirect','-camera-back','emulated','-camera-front','none','-show-kernel']
if not use_x86:emu_args+=['-feature','VirtioSndCard']
if use_x86:emu_args+=['-no-metrics']
if '--wipe' in sys.argv:emu_args+=['-wipe-data']
emu=subprocess.Popen(emu_args,env=env,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT)
subprocess.run(['adb','start-server'],env=env,timeout=20)
print(json.dumps({'event':'lab_started','emulator':str(emulator),'pid':emu.pid}),flush=True)
background=[]
def run(path):
 request=json.loads(path.read_text());rid=request['id'];start=time.monotonic()
 try:
  if request.get('background'):
   dest=root/request['log'];dest.parent.mkdir(parents=True,exist_ok=True);f=dest.open('w')
   p=subprocess.Popen(request['args'],env=env,stdin=subprocess.DEVNULL,stdout=f,stderr=subprocess.STDOUT);background.append(p)
   result={'pid':p.pid,'log':str(dest),'exit_code':None}
  else:
   p=subprocess.run(request['args'],env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=request.get('timeout',30))
   result={'exit_code':p.returncode,'output':p.stdout.decode('utf-8','replace')}
 except subprocess.TimeoutExpired as e:result={'timeout':True,'output':(e.stdout or b'').decode('utf-8','replace')}
 except Exception as e:result={'error':str(e)}
 result.update(id=rid,seconds=round(time.monotonic()-start,2));(queue/(rid+'.response.json')).write_text(json.dumps(result,indent=2))
 print(json.dumps({'event':'request_complete','id':rid,'exit_code':result.get('exit_code'),'timeout':result.get('timeout',False)}),flush=True)
def input_requests():
 for line in sys.stdin:
  try:
   request=json.loads(line)
   if request.get('stop'):(queue/'STOP').touch();return
   (queue/(request['id']+'.request.json')).write_text(json.dumps(request))
  except Exception as e:print(json.dumps({'event':'input_error','error':str(e)}),flush=True)
threading.Thread(target=input_requests,daemon=True).start()
seen=existing_requests;last=0
try:
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
  while not (queue/'STOP').exists():
   if emu.poll() is not None:
    print(json.dumps({'event':'emulator_exit','code':emu.returncode}),flush=True);break
   for path in sorted(queue.glob('*.request.json')):
    if path.name not in seen:seen.add(path.name);pool.submit(run,path)
   if time.monotonic()-last>30:
    print(json.dumps({'event':'lab_alive','elapsed':round(time.monotonic())}),flush=True);last=time.monotonic()
   time.sleep(.25)
finally:
 for p in background:
  if p.poll() is None:p.terminate()
 if emu.poll() is None:
  emu.terminate()
  try:emu.wait(timeout=15)
  except subprocess.TimeoutExpired:emu.kill()
 subprocess.run(['adb','kill-server'],env=env,timeout=10)
