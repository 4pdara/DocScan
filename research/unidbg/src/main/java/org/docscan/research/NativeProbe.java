package org.docscan.research;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.*;
import com.github.unidbg.virtualmodule.android.AndroidModule;
import com.github.unidbg.virtualmodule.android.JniGraphics;
import com.github.unidbg.Emulator;
import com.github.unidbg.linux.android.dvm.api.Bitmap;
import com.sun.jna.Pointer;
import java.io.File;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/** Function-level ARM64 emulation; this is not an Android-device or Frida test. */
public final class NativeProbe extends AbstractJni {
 static final class CaptureBitmap extends Bitmap {
  private Pointer pointer;byte[] processed;
  CaptureBitmap(VM vm,BufferedImage image){super(vm,image);}
  @Override public Pointer lockPixels(Emulator<?> emulator,BufferedImage image,ByteBuffer buffer){pointer=super.lockPixels(emulator,image,buffer);return pointer;}
  @Override public void unlockPixels(){processed=pointer.getByteArray(0,getValue().getWidth()*getValue().getHeight()*4);super.unlockPixels();}
 }
 @Override public DvmObject<?> callObjectMethodV(BaseVM vm,DvmObject<?> object,String signature,VaList args) {
  if(signature.equals("android/content/Context->getPackageName()Ljava/lang/String;"))return new StringObject(vm,"org.docscan.engineprobe");
  return super.callObjectMethodV(vm,object,signature,args);
 }
 public static void main(String[] args) throws Exception {
  File libs=new File(args[0]);
  try(AndroidEmulator emu=AndroidEmulatorBuilder.for64Bit().addBackendFactory(new Unicorn2Factory(true)).setProcessName("org.docscan.engineprobe").build()) {
   emu.getMemory().setLibraryResolver(new AndroidResolver(23));
   VM vm=emu.createDalvikVM(new File("research/probe/docscan-jni-probe.apk"));vm.setJni(new NativeProbe());vm.setVerbose(false);
   new AndroidModule(emu,vm).register(emu.getMemory());new JniGraphics(emu,vm).register(emu.getMemory());
   vm.loadLibrary(new File(libs,"libmainstone.so"),true);
   DalvikModule scanner=vm.loadLibrary(new File(libs,"libscannercs.so"),true);scanner.callJNI_OnLoad(emu);
   DvmClass engine=vm.resolveClass("com/intsig/scanner/ScannerEngine");
   System.out.println("SCANNER_VERSION="+engine.callStaticJniMethodObject(emu,"GetVersion()Ljava/lang/String;").getValue());
   DvmObject<?> context=vm.resolveClass("android/content/Context").newObject(null);
   try {
    int status=engine.callStaticJniMethodInt(emu,"initEngine(Landroid/content/Context;Ljava/lang/String;)I",context,new StringObject(vm,""));
    System.out.println("EMPTY_LICENSE_INIT="+status);
    System.out.println("THREAD_CONTEXT="+engine.callStaticJniMethodInt(emu,"initThreadContext()I"));
   }catch(Exception e){System.out.println("INIT_ENVIRONMENT_UNSUPPORTED="+e.getClass().getSimpleName());}
   for(String lib:new String[]{"opencv","ncnn","MNN","CVNN","magicenhancer"}){
    vm.loadLibrary(new File(libs,"lib"+lib+".so"),true);System.out.println("LOADED="+lib);
   }
   DvmClass magic=vm.resolveClass("com/intsig/nativelib/MagicEnhancer");
   System.out.println("MAGIC_VERSION="+magic.callStaticJniMethodObject(emu,"GetVersion()Ljava/lang/String;").getValue());
   int session=magic.callStaticJniMethodInt(emu,"initDeshadowModelNew(I)I",1);
   System.out.println("MAGIC_MODEL_SESSION="+session);
   if(session>=0) {
    try {
     for(int mode:new int[]{1,3}) {
      int width=96,height=128;BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_4BYTE_ABGR);
      for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
       int value=(y%12<3&&x>8&&x<84)?25:(130+x*110/width);
       image.setRGB(x,y,0xff000000|(value<<16)|(value<<8)|value);
      }
      ImageIO.write(image,"PNG",new File("research/unidbg-source-"+mode+".png"));
      CaptureBitmap bitmap=new CaptureBitmap(vm,image);long started=System.nanoTime();
      int result=magic.callStaticJniMethodInt(emu,"deshadowBitmapNew(Landroid/graphics/Bitmap;II)I",bitmap,mode,session);
      if(bitmap.processed!=null) {
       byte[] pixels=bitmap.processed;
       for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
        int at=(y*width+x)*4;image.setRGB(x,y,((pixels[at+3]&255)<<24)|((pixels[at]&255)<<16)|((pixels[at+1]&255)<<8)|(pixels[at+2]&255));
       }
       ImageIO.write(image,"PNG",new File("research/unidbg-mode-"+mode+".png"));
       System.out.println("MAGIC_PIXELS mode="+mode+" status="+result+" width="+width+" height="+height+" elapsed_ms="+(System.nanoTime()-started)/1000000+" sha256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pixels)));
      }else System.out.println("MAGIC_NO_PIXELS mode="+mode+" status="+result);
     }
    }finally{magic.callStaticJniMethod(emu,"releaseDeshadowModelNew(I)V",session);}
   }
  }
 }
}
