import pathlib,json,subprocess,zipfile,shutil
root=pathlib.Path(__file__).resolve().parents[1];out=root/'research/probe';src=out/'src';classes=out/'classes';dex=out/'dex'
for d in [src,classes,dex,out/'gen']:d.mkdir(parents=True,exist_ok=True)
selected={
 'Lcom/intsig/scanner/ScannerEngine;':{'GetVersion','initEngine','initThreadContext','destroyThreadContext','enhanceBitmap','detectYuvImage','decodeBitmap','getImageWidth','getImageHeight','releaseImageS','setLogLevel'},
 'Lcom/intsig/nativelib/MagicEnhancer;':{'GetVersion','initDeshadowModelNew','deshadowBitmapNew','releaseDeshadowModelNew'},
 'Lcom/intsig/nativelib/ImageProcessor;':{'GetVersion'},
 'Lcom/intsig/nativelib/PageScanner;':{'GetVersion'},
}
def types(s):
 names={'I':'int','J':'long','Z':'boolean','B':'byte','F':'float','D':'double','S':'short','C':'char','V':'void'};result=[]
 while s:
  arrays=0
  while s[0]=='[':arrays+=1;s=s[1:]
  if s[0]=='L':end=s.index(';');name=s[1:end].replace('/','.');s=s[end+1:]
  else:name=names[s[0]];s=s[1:]
  result.append(name+'[]'*arrays)
 return result
for c in json.loads((root/'research/jni_signatures.json').read_text()):
 name=c['class_name'][1:-1];pkg,simple=name.rsplit('/',1);lines=['package '+pkg.replace('/','.')+';','public final class '+simple+' {']
 for m in c['methods']:
  if not m['access']&256 or m['name'] not in selected.get(c['class_name'],set()):continue
  args,ret=m['descriptor'][1:].split(')');params=', '.join(t+' p'+str(i) for i,t in enumerate(types(args)))
  lines.append(' public static native '+types(ret)[0]+' '+m['name']+'('+params+');')
 lines+=['}'];p=src/pkg/(simple+'.java');p.parent.mkdir(parents=True,exist_ok=True);p.write_text('\n'.join(lines))
manifest='''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.docscan.engineprobe"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="28"/><application android:label="DocScan JNI Research" android:debuggable="true" android:allowBackup="false" android:extractNativeLibs="true" android:theme="@android:style/Theme.Material.Light.NoActionBar"><activity android:name=".ProbeActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>'''
(out/'AndroidManifest.xml').write_text(manifest)
jdk=root/'runtime/jdk/usr/lib/jvm/java-21-openjdk-amd64/bin';sdk=root/'runtime/buildsdk';bt=sdk/'android-15';android=sdk/'android-36/android.jar'
subprocess.run([str(jdk/'javac'),'-source','8','-target','8','-cp',str(android),'-d',str(classes)]+[str(p) for p in src.rglob('*.java')],check=True)
subprocess.run([str(bt/'d8'),'--min-api','26','--lib',str(android),'--output',str(dex)]+[str(p) for p in classes.rglob('*.class')],env={**__import__('os').environ,'JAVA_HOME':str(jdk.parent),'PATH':str(jdk)+':'+__import__('os').environ['PATH']},check=True)
subprocess.run([str(bt/'aapt2'),'link','-I',str(android),'--manifest',str(out/'AndroidManifest.xml'),'-o',str(out/'unsigned.apk'),'--version-code','1','--version-name','0.1-research'],check=True)
libraries=['libscannercs.so','libmainstone.so','libmagicenhancer.so','libimageprocessor.so','libpagescanner.so','libopencv.so','libncnn.so','libMNN.so','libCVNN.so']
with zipfile.ZipFile(out/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
 for p in dex.glob('*.dex'):z.write(p,p.name)
 for n in libraries:z.write(root/'research/private/arm64'/n,'lib/arm64-v8a/'+n)
subprocess.run([str(bt/'zipalign'),'-f','4',str(out/'unsigned.apk'),str(out/'aligned.apk')],check=True)
key=out/'research.keystore'
if not key.exists():subprocess.run([str(jdk/'keytool'),'-genkeypair','-keystore',str(key),'-storepass','android','-keypass','android','-alias','research','-dname','CN=DocScan JNI Research','-keyalg','RSA','-validity','365'],check=True)
subprocess.run([str(bt/'apksigner'),'sign','--ks',str(key),'--ks-pass','pass:android','--out',str(out/'docscan-jni-probe.apk'),str(out/'aligned.apk')],env={**__import__('os').environ,'JAVA_HOME':str(jdk.parent),'PATH':str(jdk)+':'+__import__('os').environ['PATH']},check=True)
print('probe_apk',str(out/'docscan-jni-probe.apk'))
