# Test-only resource-limit probe: changes limits only in short-lived children.
import json, subprocess, sys
child = r'''import ctypes,json,mmap,resource,sys
class Limits(ctypes.Structure):
 _fields_=[('soft',ctypes.c_uint64),('hard',ctypes.c_uint64)]
name=sys.argv[1]; bound=int(sys.argv[2]); result={'mode':name,'bound':bound}
if name != 'baseline':
 number=getattr(resource,name); limits=Limits(bound,bound)
 libc=ctypes.CDLL(None,use_errno=True); rc=libc.setrlimit(number,ctypes.byref(limits))
 result.update(number=number,set_return=rc,errno=ctypes.get_errno(),readback=resource.getrlimit(number))
if name == 'baseline':
 m=mmap.mmap(-1,32*1024*1024)
 for i in range(0,len(m),4096):m[i:i+1]=b'x'
 result['allocation']='touched-32MiB'
print(json.dumps(result))'''
for name, bound in [('baseline',0),('RLIMIT_CPU',3),('RLIMIT_FSIZE',8388608),('RLIMIT_RSS',8388608),('RLIMIT_DATA',8388608),('RLIMIT_DATA',536870912),('RLIMIT_DATA',1073741824),('RLIMIT_AS',8388608),('RLIMIT_AS',536870912),('RLIMIT_AS',1073741824)]:
 result=subprocess.run([sys.executable,'-c',child,name,str(bound)],capture_output=True,text=True,timeout=8)
 assert result.returncode==0,(name,result.returncode,result.stderr)
 print(result.stdout.strip())
