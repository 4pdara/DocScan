'use strict';
// Read-only process-lifecycle observations. No changed return values, system properties,
// SDK initialization, identity, signature, licensing, or entitlement checks.
function event(value) { console.log(JSON.stringify(Object.assign({at: Date.now()}, value))); }
Process.attachModuleObserver({onAdded(module) {
    if (/lib(exec|execmain|scannercs|mainstone|magicenhancer|imageprocessor|pagescanner)\.so$/.test(module.name)) {
        event({event: 'lifecycle_module', name: module.name, arch: Process.arch});
    }
}});
const libc = Process.getModuleByName('libc.so');
for (const name of ['exit', '_exit', 'abort', 'raise', 'kill', 'pthread_kill']) {
    const address = libc.findExportByName(name);
    if (!address) continue;
    Interceptor.attach(address, {onEnter(args) {
        const value = name === 'abort' ? null : args[name === 'kill' || name === 'pthread_kill' ? 1 : 0].toInt32();
        event({event: 'lifecycle_exit', function: name, status_or_signal: value});
    }});
}
event({event: 'lifecycle_ready', arch: Process.arch});
