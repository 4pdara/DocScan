import pathlib,struct,json
targets={'Lcom/intsig/scanner/ScannerEngine;','Lcom/intsig/nativelib/MagicEnhancer;','Lcom/intsig/nativelib/ImageProcessor;','Lcom/intsig/nativelib/PageScanner;'}
found=[]
for p in pathlib.Path('research/private/base').glob('*.dex'):
 b=p.read_bytes()
 def u32(at):return struct.unpack_from('<I',b,at)[0]
 def uleb(at):
  val=0;shift=0
  while True:
   c=b[at];at+=1;val|=(c&127)<<shift;shift+=7
   if c<128:return val,at
 n,o=struct.unpack_from('<II',b,56);ss=[]
 for i in range(n):
  at=u32(o+4*i);_,at=uleb(at);ss.append(b[at:b.find(b'\0',at)].decode('utf-8','replace'))
 n,o=struct.unpack_from('<II',b,64);types=[ss[u32(o+4*i)] for i in range(n)]
 np,op=struct.unpack_from('<II',b,72);protos=[]
 for i in range(np):
  _,ret,args=struct.unpack_from('<III',b,op+12*i)
  argtypes=[] if not args else [types[struct.unpack_from('<H',b,args+4+2*j)[0]] for j in range(u32(args))]
  protos.append('('+''.join(argtypes)+')'+types[ret])
 nm,om=struct.unpack_from('<II',b,88)
 methods=[]
 for i in range(nm):
  cls,proto,name=struct.unpack_from('<HHI',b,om+8*i);methods.append((types[cls],ss[name],protos[proto]))
 nc,oc=struct.unpack_from('<II',b,96)
 for i in range(nc):
  at=oc+32*i;cls=types[u32(at)]
  if cls not in targets:continue
  data=u32(at+24);counts=[]
  for j in range(4):v,data=uleb(data);counts.append(v)
  for j in range(counts[0]+counts[1]):_,data=uleb(data);_,data=uleb(data)
  rows=[]
  for count in counts[2:]:
   idx=0
   for j in range(count):
    diff,data=uleb(data);idx+=diff;access,data=uleb(data);code,data=uleb(data)
    rows.append(dict(name=methods[idx][1],descriptor=methods[idx][2],access=access,code=code))
  found.append(dict(dex=p.name,class_name=cls,methods=rows))
  print(p.name,cls,flush=True)
  for row in rows:
   if row['access']&256:print(' native',row['name'],row['descriptor'],'static',bool(row['access']&8),flush=True)
pathlib.Path('research/jni_signatures.json').write_text(json.dumps(found,indent=2))
