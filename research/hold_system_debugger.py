"""Attach a normal JDWP debugger while the software-emulated framework initializes."""
import pathlib,subprocess,time,os
root=pathlib.Path(__file__).resolve().parents[1]
def adb(*args):return subprocess.check_output(['adb']+list(args),timeout=20).decode().strip()
pid=None
for attempt in range(90):
 try:pid=adb('shell','-n','pidof','system_server').split()[0];break
 except (subprocess.SubprocessError,IndexError):time.sleep(2)
if not pid:raise RuntimeError('system_server did not start')
print('system_server_pid',pid,flush=True)
adb('forward','tcp:8700','jdwp:'+pid)
jdk=root/'runtime/jdk/usr/lib/jvm/java-21-openjdk-amd64';env=os.environ.copy();env['JAVA_HOME']=str(jdk)
p=subprocess.Popen([str(jdk/'bin/jdb'),'-attach','127.0.0.1:8700'],env=env,stdin=subprocess.PIPE)
time.sleep(4)
p.stdin.write(b'cont\n');p.stdin.flush()
try:p.wait(timeout=900)
except subprocess.TimeoutExpired:p.stdin.write(b'exit\n');p.stdin.flush();p.wait(timeout=15)
