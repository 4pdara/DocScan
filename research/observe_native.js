'use strict';
function event(v){console.log(JSON.stringify(Object.assign({at:Date.now()},v)));}
event({event:'native_agent',arch:Process.arch});
if(Process.arch!=='arm64')throw Error('This script requires an ARM64 process or emulated realm');
const seen=new Set();
Process.attachModuleObserver({onAdded(module){
 if(!/^lib(scannercs|magicenhancer|imageprocessor|pagescanner)\.so$/.test(module.name))return;
 event({event:'arm_module',name:module.name,size:module.size});
 for(const symbol of module.enumerateExports()){
  if(symbol.type!=='function'||!/^Java_com_intsig_/.test(symbol.name))continue;
  const key=module.name+'!'+symbol.name;if(seen.has(key))continue;seen.add(key);
  try{Interceptor.attach(symbol.address,{
   onEnter(args){this.start=Date.now();},
   onLeave(ret){event({event:'arm_jni_return',name:symbol.name,result:symbol.name.endsWith('_GetVersion')?'jstring':ret.toInt32(),elapsed_ms:Date.now()-this.start});}
  });}catch(e){event({event:'attach_error',name:symbol.name,message:String(e)});}
 }
}});
