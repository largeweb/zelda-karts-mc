"""Small typed NBT reader/writer for creating local world metadata (no assets)."""
import struct,gzip,io

def read(path):
 f=io.BytesIO(gzip.decompress(path.read_bytes()))
 def number(fmt):return struct.unpack('>'+fmt,f.read(struct.calcsize(fmt)))[0]
 def string():return f.read(number('H')).decode('utf-8')
 def payload(t):
  if t in range(1,7):return number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[t])
  if t==8:return string()
  if t==9:
   kind=number('B');return (kind,[payload(kind) for _ in range(number('i'))])
  if t==10:
   d={}
   while (kind:=number('B')):name=string();d[name]=(kind,payload(kind))
   return d
  if t in (7,11,12):return [number({7:'b',11:'i',12:'q'}[t]) for _ in range(number('i'))]
  raise ValueError(t)
 t=number('B');name=string();return name,(t,payload(t))
def write(path,root,name=''):
 f=io.BytesIO()
 def number(fmt,v):f.write(struct.pack('>'+fmt,v))
 def string(v):b=v.encode('utf-8');number('H',len(b));f.write(b)
 def payload(t,v):
  if t in range(1,7):number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[t],v)
  elif t==8:string(v)
  elif t==9:
   kind,items=v;number('B',kind);number('i',len(items))
   for item in items:payload(kind,item)
  elif t==10:
   for key,(kind,value) in v.items():number('B',kind);string(key);payload(kind,value)
   number('B',0)
  elif t in (7,11,12):
   number('i',len(v))
   for item in v:number({7:'b',11:'i',12:'q'}[t],item)
  else:raise ValueError(t)
 number('B',root[0]);string(name);payload(*root);path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(gzip.compress(f.getvalue(),mtime=0))
