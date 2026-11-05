package org.docscan.engineprobe;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.widget.TextView;
import android.util.Log;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import com.intsig.scanner.ScannerEngine;
import com.intsig.nativelib.MagicEnhancer;
import com.intsig.nativelib.ImageProcessor;
import com.intsig.nativelib.PageScanner;

public final class ProbeActivity extends Activity {
 private TextView view;
 private void line(String s) {
  Log.i("DocScanJNIProbe",s);
  try(FileWriter w=new FileWriter(new File(getFilesDir(),"probe.txt"),true)){w.write(s+"\n");}catch(IOException ignored){}
  runOnUiThread(()->view.append(s+"\n"));
 }
 @Override public void onCreate(Bundle b) {
  super.onCreate(b);view=new TextView(this);view.setTextSize(16);view.setPadding(20,30,20,20);setContentView(view);
  new Thread(this::probe,"jni-research").start();
 }
 private static String hash(Bitmap b) throws Exception {
  ByteBuffer pixels=ByteBuffer.allocate(b.getByteCount());b.copyPixelsToBuffer(pixels);
  byte[] h=MessageDigest.getInstance("SHA-256").digest(pixels.array());StringBuilder s=new StringBuilder();for(byte v:h)s.append(String.format("%02x",v&255));return s.toString();
 }
 private void probe() {
  line("package="+getPackageName()+" abi="+Arrays.toString(android.os.Build.SUPPORTED_ABIS)+" sdk="+android.os.Build.VERSION.SDK_INT);
  try {
   System.loadLibrary("mainstone");System.loadLibrary("scannercs");line("scannercs.load=success");
   line("scannercs.version="+ScannerEngine.GetVersion());
   for(String lib:new String[]{"opencv","ncnn","MNN","CVNN","magicenhancer","imageprocessor","pagescanner"}) {
    try{System.loadLibrary(lib);line(lib+".load=success");}catch(Throwable t){line(lib+".load="+t.toString());}
   }
   try{line("magic.version="+MagicEnhancer.GetVersion());}catch(Throwable t){line("magic.version.error="+t);}
   try{line("processor.version="+ImageProcessor.GetVersion());}catch(Throwable t){line("processor.version.error="+t);}
   try{line("page.version="+PageScanner.GetVersion());}catch(Throwable t){line("page.version.error="+t);}
   line("initEngine=skipped; valid SDK initialization data not supplied");
   int init=-1;
   int context=0;
   if(init!=0||context<=0){line("processing=blocked_by_normal_initialization; no verification result modified");magicProbe();line("DONE");return;}
   try {
    Bitmap source=Bitmap.createBitmap(384,512,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(source);canvas.drawColor(Color.rgb(70,70,80));Paint p=new Paint();p.setColor(Color.rgb(210,202,183));canvas.drawRect(32,24,352,488,p);p.setColor(Color.rgb(40,35,30));p.setTextSize(12);for(int i=0;i<24;i++)canvas.drawText("DocScan JNI fixture 0123456789",42,52+i*16,p);
    try(FileOutputStream f=new FileOutputStream(new File(getFilesDir(),"source.png"))){source.compress(Bitmap.CompressFormat.PNG,100,f);}
    String before=hash(source);line("fixture.source.sha256="+before);
    for(int mode:new int[]{10,13,19,17}) {
     Bitmap copy=source.copy(Bitmap.Config.ARGB_8888,true);long start=System.nanoTime();
     int status=ScannerEngine.enhanceBitmap(context,copy,mode);
     line("enhance.mode="+mode+" status="+status+" width="+copy.getWidth()+" height="+copy.getHeight()+" elapsed_ms="+((System.nanoTime()-start)/1000000)+" sha256="+hash(copy));
     try(FileOutputStream f=new FileOutputStream(new File(getFilesDir(),"mode-"+mode+".png"))){copy.compress(Bitmap.CompressFormat.PNG,100,f);}copy.recycle();
    }
    line("source_unchanged="+before.equals(hash(source)));source.recycle();
   } finally {line("destroyThreadContext.result="+ScannerEngine.destroyThreadContext(context));}
   magicProbe();
  }catch(Throwable t){line("ERROR "+t);}
  line("DONE");
 }
 private void magicProbe() throws Exception {
  int session=MagicEnhancer.initDeshadowModelNew(1);
  line("magic.init.threads=1 session="+session);
  if(session<0)return;
  try {
   Bitmap source=Bitmap.createBitmap(192,256,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(source);canvas.drawColor(Color.rgb(210,200,180));Paint p=new Paint();p.setColor(Color.rgb(30,25,20));p.setTextSize(10);for(int i=0;i<16;i++)canvas.drawText("JNI fixture 012345",10,18+i*14,p);
   String before=hash(source);line("magic.source.sha256="+before);
   try(FileOutputStream f=new FileOutputStream(new File(getFilesDir(),"magic-source.png"))){source.compress(Bitmap.CompressFormat.PNG,100,f);}
   for(int mode:new int[]{1,3}) {
    Bitmap copy=source.copy(Bitmap.Config.ARGB_8888,true);long start=System.nanoTime();
    int status=MagicEnhancer.deshadowBitmapNew(copy,mode,session);
    line("magic.mode="+mode+" status="+status+" width="+copy.getWidth()+" height="+copy.getHeight()+" elapsed_ms="+((System.nanoTime()-start)/1000000)+" sha256="+hash(copy));
    try(FileOutputStream f=new FileOutputStream(new File(getFilesDir(),"magic-mode-"+mode+".png"))){copy.compress(Bitmap.CompressFormat.PNG,100,f);}copy.recycle();
   }
   line("magic.source_unchanged="+before.equals(hash(source)));source.recycle();
  }finally{MagicEnhancer.releaseDeshadowModelNew(session);line("magic.release=called");}
 }
}
