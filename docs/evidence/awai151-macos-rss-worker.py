# One-off 32 MiB test process; not production tooling.
import json,mmap,os,resource,sys,time
if len(sys.argv)>2 and sys.argv[2]=="explicit":resource.setrlimit(resource.RLIMIT_RSS,(8*1024*1024,8*1024*1024))
size=32*1024*1024
m=mmap.mmap(-1,size)
for offset in range(0,size,4096):m[offset:offset+1]=b'x'
data={'pid':os.getpid(),'requestedBytes':size,'rssLimit':list(resource.getrlimit(resource.RLIMIT_RSS)),'maxrssRaw':resource.getrusage(resource.RUSAGE_SELF).ru_maxrss}
with open(sys.argv[1],'w') as f:json.dump(data,f);f.flush();os.fsync(f.fileno())
time.sleep(4)
