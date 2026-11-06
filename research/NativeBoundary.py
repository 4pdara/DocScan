# Ghidra post-script; private outputs are intentionally not committed.
#@category DocScan.Research
from ghidra.app.decompiler import DecompInterface
from java.io import File
from ghidra.program.model.data import IntegerDataType, LongLongDataType, FloatDataType, DoubleDataType, PointerDataType, VoidDataType, ByteDataType
from ghidra.program.model.listing import ParameterImpl, Function
from ghidra.program.model.symbol import SourceType
import os,json

args=getScriptArgs()
dest=args[0]
if not os.path.exists(dest):os.makedirs(dest)
if len(args)>1:
 def parse_types(text):
  values=[]
  while text:
   array=False
   while text[0]=='[':array=True;text=text[1:]
   first=text[0]
   if first=='L':text=text[text.index(';')+1:];t=PointerDataType()
   else:
    text=text[1:]
    t={'I':IntegerDataType.dataType,'J':LongLongDataType.dataType,'F':FloatDataType.dataType,'D':DoubleDataType.dataType,'Z':ByteDataType.dataType,'B':ByteDataType.dataType,'V':VoidDataType.dataType}.get(first,IntegerDataType.dataType)
   values.append(PointerDataType() if array else t)
  return values
 signatures={}
 for cls in json.load(open(args[1])):
  prefix='Java_'+cls['class_name'][1:-1].replace('/','_')+'_'
  for m in cls['methods']:
   if m['access']&256:signatures[prefix+m['name']]=m['descriptor']
 for f in currentProgram.getFunctionManager().getFunctions(True):
  descriptor=signatures.get(f.getName())
  if descriptor:
   argstr,retstr=descriptor[1:].split(')')
   ts=[PointerDataType(),PointerDataType()]+parse_types(argstr)
   ps=[ParameterImpl('arg'+str(i),t,currentProgram) for i,t in enumerate(ts)]
   f.setReturnType(parse_types(retstr)[0],SourceType.USER_DEFINED)
   f.replaceParameters(Function.FunctionUpdateType.DYNAMIC_STORAGE_ALL_PARAMS,True,SourceType.USER_DEFINED,ps)
tokens=['JNI_OnLoad','ScannerEngine_initEngine','ScannerEngine_initThreadContext',
'ScannerEngine_enhanceBitmap','ScannerEngine_enhanceImageS','ScannerEngine_decodeBitmap',
'ScannerEngine_releaseImageS','ScannerEngine_detectImageS','ScannerEngine_trimBitmap',
'ScannerEngine_GetVersion','MagicEnhancer_initDeshadowModelNew','MagicEnhancer_deshadowBitmapNew',
'MagicEnhancer_ImageSilverBulletBitmap','MagicEnhancer_GetVersion','PageScanner_initCropDewarp',
'PageScanner_initDetectBorder','PageScanner_DetectBorder','PageScanner_GetVersion',
'ImageProcessor_initDeblur','ImageProcessor_GetVersion','EnhanceColorImageByModes','init_model',
'MagicEnhancer_deshadowBitmap','MagicEnhancer_deshadowBitmap2','MagicEnhancer_initDeshadowModel2',
'MagicEnhancer_releaseDeshadowModel2','MagicEnhancer_classifyShadowTypeBitmap',
'ImageProcessor_ReflectBinaryColorShiftBitmapBG']
di=DecompInterface();di.openProgram(currentProgram)
for f in currentProgram.getFunctionManager().getFunctions(True):
 if any(t==f.getName() or t in f.getName() for t in tokens):
  result=di.decompileFunction(f,90,monitor)
  filename=os.path.join(dest,f.getName()+'_'+str(f.getEntryPoint())+'.c')
  with open(filename,'w') as out:
   out.write('// '+currentProgram.getName()+' @ '+str(f.getEntryPoint())+'\n')
   if result.decompileCompleted():out.write(result.getDecompiledFunction().getC())
   else:out.write('// FAILED '+result.getErrorMessage())
  print('BOUNDARY '+f.getName()+' '+str(f.getEntryPoint()))
di.dispose()

