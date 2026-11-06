"""Observe an unmodified, signed, offline reference app; never patch its behavior."""
import json
from pathlib import Path
import sys
import threading
import time
import frida

out = Path('research-original-results')
out.mkdir(exist_ok=True)
report = {'events': [], 'outcome': 'not_started'}
lock = threading.Lock()
done = threading.Event()

def record(value):
    with lock:
        report['events'].append(value)
        print(json.dumps(value), flush=True)

def message(source):
    def receive(msg, _data):
        if msg.get('type') == 'log':
            try:
                event = json.loads(msg['payload'])
            except (TypeError, ValueError):
                return
            event['observation_source'] = source
            record(event)
            if event.get('event') == 'fixture_done':
                done.set()
        elif msg.get('type') == 'error':
            record({'event': 'observer_error', 'source': source, 'description': msg.get('description')})
    return receive

sessions = []
scripts = []
device = frida.get_device_manager().add_remote_device('127.0.0.1:27042')
try:
    # Capture loader/exit behavior before the same app follows its own startup path.
    pid = device.spawn(['com.intsig.camscanner'])
    report['pid'] = pid
    native = device.attach(pid)
    sessions.append(native)
    native.on('detached', lambda reason, crash=None: (record({'event': 'process_detached', 'reason': reason, 'crash_present': crash is not None}), done.set()))
    for filename in ['observe_lifecycle.js', 'observe_engine.js']:
        script = native.create_script((Path('research') / filename).read_text())
        script.on('message', message(filename))
        script.load()
        scripts.append(script)
    device.resume(pid)
    report['outcome'] = 'observing'
    # The emulated ARM realm is separate; Java results retain declared return types.
    try:
        arm = device.attach(pid, realm='emulated')
        sessions.append(arm)
        script = arm.create_script(Path('research/observe_native.js').read_text())
        script.on('message', message('observe_native.js'))
        script.load()
        scripts.append(script)
    except frida.Error as exc:
        record({'event': 'arm_attach_unavailable', 'error_type': type(exc).__name__})
    done.wait(110)
    report['outcome'] = 'completed' if any(e.get('event') == 'fixture_done' for e in report['events']) else 'startup_or_fixture_incomplete'
except frida.Error as exc:
    report['outcome'] = 'frida_error'
    report['error_type'] = type(exc).__name__
finally:
    for session in reversed(sessions):
        try:
            session.detach()
        except frida.Error:
            pass
    (out / 'startup-observation.json').write_text(json.dumps(report, indent=2))
print(json.dumps({'outcome': report['outcome'], 'events': len(report['events'])}), flush=True)
