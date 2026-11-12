"""Build a CI artifact delta for local signing; payloads never enter Git."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import struct
import zipfile
from publish_native_release import compressed_entry, restore


def build(baseline, current, output):
    output.mkdir(parents=True, exist_ok=True)
    data = current.read_bytes(); literal = bytearray(); segments = []
    def add(start, end):
        if end > start:
            segments.append(dict(literal_offset=len(literal), length=end-start))
            literal.extend(data[start:end])
    with zipfile.ZipFile(baseline) as previous, zipfile.ZipFile(current) as target:
        names=set(previous.namelist());cursor=0
        for item in sorted(target.infolist(),key=lambda item:item.header_offset):
            name_len,extra_len=struct.unpack_from('<HH',data,item.header_offset+26)
            start=item.header_offset+30+name_len+extra_len;end=start+item.compress_size
            add(cursor,start);payload=data[start:end]
            if item.filename in names and compressed_entry(baseline,item.filename)==payload:
                segments.append(dict(base_entry=item.filename,length=len(payload),sha256=hashlib.sha256(payload).hexdigest()))
            else:add(start,end)
            cursor=end
        add(cursor,len(data))
    compressed=gzip.compress(bytes(literal),mtime=0);payload=output/'payload.gz';payload.write_bytes(compressed)
    plan=dict(apk_name=current.name,baseline_sha256=hashlib.sha256(baseline.read_bytes()).hexdigest(),apk_sha256=hashlib.sha256(data).hexdigest(),apk_size=len(data),payload_size=len(compressed),payload_sha256=hashlib.sha256(compressed).hexdigest(),literal_size=len(literal),segments=segments)
    (output/'plan.json').write_text(json.dumps(plan,indent=2)+'\n')
    restored,_=restore(plan,baseline,payload,output/'self-check')
    assert restored.read_bytes()==data
    restored.unlink();(output/'self-check'/'SHA256SUMS.txt').unlink();(output/'self-check').rmdir()
    print(current.name,'delta bytes',len(compressed),'APK SHA256',plan['apk_sha256'])


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--baseline',required=True,type=Path);p.add_argument('--apk',required=True,type=Path);p.add_argument('--output',required=True,type=Path)
    a=p.parse_args();build(a.baseline,a.apk,a.output)
