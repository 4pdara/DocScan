'use strict';
function event(value) { console.log(JSON.stringify(Object.assign({at:Date.now()},value))); }
Java.perform(function(){
 const Bitmap=Java.use('android.graphics.Bitmap');
 const System=Java.use('java.lang.System');
 for(const cls of ['com.intsig.nativelib.MagicEnhancer','com.intsig.scanner.ScannerEngine','com.intsig.nativelib.ImageProcessor']) {
  try {
   const C=Java.use(cls);let count=0;const seen=new Set();
   for(const method of C.class.getDeclaredMethods()) {
    const name=method.getName().toString();
    if(seen.has(name)||!C[name])continue;seen.add(name);
    for(const overload of C[name].overloads) {
     const signature=overload.argumentTypes.map(t=>t.className);
     overload.implementation=function(){
      const args=Array.from(arguments);
      const metadata=args.map((v,i)=>signature[i]==='android.graphics.Bitmap'&&v?{width:v.getWidth(),height:v.getHeight()}:typeof v==='number'||typeof v==='boolean'?v:signature[i]==='java.lang.String'?'[redacted]':signature[i]);
      const start=Date.now();const result=overload.apply(this,args);
      event({event:'java_return',class:cls,method:name,signature:signature,args:metadata,result:typeof result==='number'||typeof result==='boolean'?result:overload.returnType.className,elapsed_ms:Date.now()-start});
      return result;
     };count++;
    }
   }
   event({event:'java_hooks',class:cls,count:count});
  }catch(e){event({event:'java_class_unavailable',class:cls,message:String(e)});}
 }
 event({event:'java_ready',arch:Process.arch});
});
