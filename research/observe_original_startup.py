"""Observe an unmodified, signed, offline reference app; never patch its behavior."""
import json
from pathlib import Path
import sys
import threading
import time
import frida
import frida_tools
from frida_tools.application import ConsoleApplication

out = Path('research-original-results')
out.mkdir(exist_ok=True)
report = {'events': [], 'outcome': 'not_started'}
lock = threading.Lock()
done = threading.Event()

def record(value):
    with lock:
        report['events'].append(value)
        print(json.dumps(value), flush=True)

def message(source, script):
    def receive(msg, _data):
        # Frida 17 tools supply bridges on demand; preserve the official loader.
        if ConsoleApplication.try_handle_bridge_request(None, msg, script):
            return
        if msg.get('type') == 'error':
            record({'event': 'observer_error', 'source': source, 'description': msg.get('description')})
    return receive

def log_message(source):
    def receive(level, text):
        try:
            event = json.loads(text)
        except (TypeError, ValueError):
            return
        event['observation_source'] = source
        record(event)
        if event.get('event') == 'fixture_done':
            done.set()
    return receive

def agent_source(filename):
    text = (Path('research') / filename).read_text()
    if filename != 'observe_engine.js':
        return text
    # Match frida-tools 14.11.0's packaged REPL setup. The bridge remains a
    # pinned installed tool dependency, never copied into this repository.
    runtime = (Path(frida_tools.__file__).parent / 'repl_agent.js').read_text()
    if not runtime.startswith('📦\n'):
        raise RuntimeError('Unexpected pinned Frida REPL bundle format')
    wrapped = 'Script.evaluate(' + json.dumps('/docscan/' + filename) + ',' + json.dumps(text) + ');'
    fragment = str(len(wrapped.encode('utf-8'))) + ' /docscan/observer-entry.js\n✄\n' + wrapped
    return '📦\n' + runtime[2:] + '\n✄\n' + fragment

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
        script = native.create_script(agent_source(filename))
        script.on('message', message(filename, script))
        script.set_log_handler(log_message(filename))
        script.load()
        scripts.append(script)
    device.resume(pid)
    report['outcome'] = 'observing'
    # The emulated ARM realm is separate; Java results retain declared return types.
    try:
        arm = device.attach(pid, realm='emulated')
        sessions.append(arm)
        script = arm.create_script(Path('research/observe_native.js').read_text())
        script.on('message', message('observe_native.js', script))
        script.set_log_handler(log_message('observe_native.js'))
        script.load()
        scripts.append(script)
    except Exception as exc:
        record({'event': 'arm_attach_unavailable', 'error_type': type(exc).__name__})
    done.wait(110)
    report['outcome'] = 'completed' if any(e.get('event') == 'fixture_done' for e in report['events']) else 'startup_or_fixture_incomplete'
except Exception as exc:
    report['outcome'] = 'frida_error'
    report['error_type'] = type(exc).__name__
finally:
    for session in reversed(sessions):
        try:
            session.detach()
        except Exception:
            pass
    (out / 'startup-observation.json').write_text(json.dumps(report, indent=2))
print(json.dumps({'outcome': report['outcome'], 'events': len(report['events'])}), flush=True)
