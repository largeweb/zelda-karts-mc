#!/usr/bin/env python3
"""Reject known private/generated content from Git's index before sharing."""
from pathlib import Path
import re,subprocess,sys
root=Path(__file__).resolve().parents[1]
files=subprocess.check_output(['git','-C',str(root),'ls-files','--stage','-z']).split(b'\0')
errors=[];count=0
allowed_suffix={'.py','.java','.cpp','.h','.json','.md','.txt','.patch','.sh','.fsh','.vsh','.yml'}
for entry in files:
    if not entry:continue
    meta,name=entry.split(b'\t',1);path=name.decode();count+=1
    mode,oid,stage=meta.decode().split()
    if stage!='0' or mode not in ('100644','100755'):errors.append(path+': non-regular file or unresolved entry');continue
    if path not in ('hyrule','.gitignore','.gitattributes','LICENSE') and Path(path).suffix not in allowed_suffix:
        errors.append(path+': unexpected file type')
    if any(part in ('.local','build','logs','saves','backups','sessions','libraries') for part in Path(path).parts) or Path(path).name in ('local.json','instance.cfg','accounts.json','DirtTexture.h'):
        errors.append(path+': private/generated path')
    data=subprocess.check_output(['git','-C',str(root),'cat-file','blob',oid])
    if b'\0' in data or len(data)>250000:errors.append(path+': binary or oversized file')
    if re.search(rb'/home/(?!YOUR_USER\b)[A-Za-z0-9_.-]+/',data):errors.append(path+': machine-specific home path')
    if re.search(rb'"(?:accessToken|refreshToken|clientToken)"\s*:',data):errors.append(path+': credential field')
if errors:
    print('\n'.join(errors));sys.exit(1)
if not count:raise SystemExit('No staged/tracked files to audit; git add the source files first.')
print(f'PASS: {count} indexed files; no known game assets, credentials, saves, private paths, or generated binaries.')
print('This checks the index, not prior Git history. Start from this fresh repository.')
