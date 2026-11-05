'use strict';
// Observe the normal application path. Never replace license/authentication results.
function event(value) { console.log(JSON.stringify(Object.assign({at:Date.now()},value))); }
const observed = new Set();
Process.attachModuleObserver({
  onAdded(module) {
    if (!/^lib(scannercs|mainstone|magicenhancer|imageprocessor|pagescanner)\.so$/.test(module.name)) return;
    event({event:'module',name:module.name,size:module.size,arch:Process.arch});
    if(Process.arch!=='arm64'){event({event:'native_trace_deferred',reason:'ARM64 native tracing requires the emulated realm'});return;}
    for (const symbol of module.enumerateExports()) {
      if (symbol.type !== 'function' || !/^Java_com_intsig_(scanner_ScannerEngine|nativelib_(MagicEnhancer|ImageProcessor|PageScanner))_/.test(symbol.name)) continue;
      const key=module.name+'!'+symbol.name;
      if(observed.has(key))continue;observed.add(key);
      Interceptor.attach(symbol.address,{
        onEnter(args) { this.start=Date.now(); },
        onLeave(result) { event({event:'native_return',name:symbol.name,result:symbol.name.endsWith('_GetVersion')?'jstring':result.toInt32(),elapsed_ms:Date.now()-this.start}); }
      });
    }
  }
});

let fixtureScheduled=false;
function fixture() {
 Java.perform(function() {
  const Engine=Java.use('com.intsig.scanner.ScannerEngine');
  const ActivityThread=Java.use('android.app.ActivityThread');
  const Factory=Java.use('android.graphics.BitmapFactory');
  const BitmapConfig=Java.use('android.graphics.Bitmap$Config');
  const Format=Java.use('android.graphics.Bitmap$CompressFormat');
  const File=Java.use('java.io.File');const Output=Java.use('java.io.FileOutputStream');
  const context=ActivityThread.currentApplication().getApplicationContext();
  const source=Factory.decodeFile('/sdcard/Download/DocScan-Research-Input.png');
  if(source===null){event({event:'fixture_error',reason:'input_not_readable'});return;}
  const dir=File.$new(context.getFilesDir(),'docscan-research');dir.mkdirs();
  const handle=Engine.initThreadContext();
  event({event:'fixture_context',handle:handle});
  if(handle<=0){source.recycle();return;}
  try {
   for(const mode of [10,13,19,17]) {
    const bitmap=source.copy(BitmapConfig.ARGB_8888.value,true);const start=Date.now();
    const result=Engine.enhanceBitmap(handle,bitmap,mode);
    const filename=File.$new(dir,'mode-'+mode+'.png');const stream=Output.$new(filename);
    try{bitmap.compress(Format.PNG.value,100,stream);}finally{stream.close();}
    event({event:'fixture_result',mode:mode,status:result,width:bitmap.getWidth(),height:bitmap.getHeight(),elapsed_ms:Date.now()-start});
    bitmap.recycle();
   }
  }finally{Engine.destroyThreadContext(handle);source.recycle();}
  event({event:'fixture_done'});
 });
}
Java.perform(function() {
 for(const cls of ['com.intsig.scanner.ScannerEngine','com.intsig.nativelib.MagicEnhancer','com.intsig.nativelib.ImageProcessor','com.intsig.nativelib.PageScanner']) {
  try {
   const C=Java.use(cls);const methods=C.class.getDeclaredMethods();
   const names=new Set();for(let i=0;i<methods.length;i++)names.add(String(methods[i].getName()));
   for(const name of names) {
    if(!C[name]||!C[name].overloads)continue;
    for(const overload of C[name].overloads) {
     const signature=overload.argumentTypes.map(t=>t.className);
     overload.implementation=function() {
      const args=Array.prototype.slice.call(arguments);const start=Date.now();
      const metadata=args.map((v,i)=>{
       const type=signature[i];
       if(type==='java.lang.String')return {type:type,redacted:true};
       if(type==='android.graphics.Bitmap')return v?{type:type,width:v.getWidth(),height:v.getHeight(),config:String(v.getConfig())}:null;
       if(['int','boolean','float','double','long'].indexOf(type)>=0)return {type:type,value:String(v)};
       return {type:type};
      });
      const result=overload.apply(this,args);
      const returnType=overload.returnType.className;
      const resultInfo=['int','boolean','float','double','long','void'].indexOf(returnType)>=0?String(result):(name==='GetVersion'?String(result):returnType);
      event({event:'java_return',class:cls,name:name,signature:signature,args:metadata,result:resultInfo,elapsed_ms:Date.now()-start});
      if(name==='initEngine'&&result===0&&!fixtureScheduled){fixtureScheduled=true;setTimeout(fixture,3000);}
      return result;
     };
    }
   }
   event({event:'java_hooks',class:cls});
  }catch(e){event({event:'hook_error',class:cls,message:String(e)});}
 }
 event({event:'hooks_ready',arch:Process.arch});
});

setTimeout(function(){
 if(fixtureScheduled)return;
 Java.perform(function(){
  try{
   const Engine=Java.use('com.intsig.scanner.ScannerEngine');
   const handle=Engine.initThreadContext();
   event({event:'late_context_probe',handle:handle});
   if(handle>0){Engine.destroyThreadContext(handle);fixtureScheduled=true;setTimeout(fixture,1);}
  }catch(e){event({event:'late_context_unavailable',message:String(e)});}
 });
},60000);
