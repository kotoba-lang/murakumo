# Task-owned observation fixture; not an operational service.
import fcntl,json,os,sys,time
root,escape=sys.argv[1],sys.argv[2]=='1'
fd=os.open(root+'/slot.lock',os.O_CREAT|os.O_RDWR,0o600)
fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
parent=os.getpid();child=os.fork()
if child==0:
 os.close(fd)
 if escape:os.setsid()
 with open(root+'/ready.json','w') as f:json.dump({'parent':parent,'child':os.getpid(),'group':os.getpgrp()},f);f.flush();os.fsync(f.fileno())
 time.sleep(8);os._exit(0)
time.sleep(8)
