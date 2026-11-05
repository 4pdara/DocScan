import hashlib, json, pathlib, zipfile
from elftools.elf.elffile import ELFFile

root=pathlib.Path(__file__).resolve().parents[1]
source=next((root/'reference').rglob('*.apks.zip'))
print('reference_sha256',hashlib.sha256(source.read_bytes()).hexdigest(),flush=True)
dest=root/'research'/'private'; dest.mkdir(exist_ok=True)
inventory=[]
with zipfile.ZipFile(source) as outer:
    for item in outer.namelist():
        if item.endswith('.apk'):
            p=dest/pathlib.Path(item).name; p.write_bytes(outer.read(item))
            with zipfile.ZipFile(p) as z:
                for entry in z.namelist():
                    if entry.startswith('lib/arm64-v8a/') and entry.endswith('.so'):
                        out=dest/'arm64'/pathlib.Path(entry).name;out.parent.mkdir(exist_ok=True);out.write_bytes(z.read(entry))
                    elif pathlib.Path(item).name=='base.apk' and (entry.startswith('assets/') or entry.endswith('.dex')):
                        out=dest/'base'/entry
                        if '..' not in out.parts:out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(z.read(entry))
for p in sorted((dest/'arm64').glob('*.so')):
    with p.open('rb') as f:
        elf=ELFFile(f)
        dynamic=next(elf.get_section(i) for i in range(elf.num_sections()) if elf._get_section_header(i)['sh_type']=='SHT_DYNAMIC')
        syms=next(elf.get_section(i) for i in range(elf.num_sections()) if elf._get_section_header(i)['sh_type']=='SHT_DYNSYM')
        needed=[t.needed for t in dynamic.iter_tags() if t.entry.d_tag=='DT_NEEDED']
        exports=[s.name for s in syms.iter_symbols() if s['st_shndx']!='SHN_UNDEF']
        imports=[s.name for s in syms.iter_symbols() if s['st_shndx']=='SHN_UNDEF']
        inventory.append(dict(name=p.name,size=p.stat().st_size,needed=needed,exports=exports,imports=imports,sha256=hashlib.sha256(p.read_bytes()).hexdigest()))
(root/'research'/'elf_inventory.json').write_text(json.dumps(inventory,indent=2))
for v in inventory:
    if any(t in v['name'] for t in ['scan','image','magic','mainstone']):
        print(v['name'],v['size'],'NEEDED',v['needed'],'EXPORTS',len(v['exports']),flush=True)
print('arm64_library_count',len(inventory),'assets',len(list((dest/'base'/'assets').rglob('*'))),flush=True)
