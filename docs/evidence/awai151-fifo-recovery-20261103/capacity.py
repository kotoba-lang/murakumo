# Test-only: bounded queue capacity; no real resource admission.
import os,pathlib,subprocess
p=pathlib.Path('/private/tmp/awai151-fifo-recovery');root=p/'capacity';root.mkdir();(root/'queue').write_text('RGQ1\n');(root/'queue.lock').write_text('');fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
def run(op,ticket=None):
 a=[str(p/'fifo.macho'),'/capacity',op]+([ticket] if ticket else []);r=subprocess.run(a,cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=8);return r.returncode,r.stdout.strip(),r.stderr
try:
 for i in range(64):
  r=run('enqueue',format(i,'064x'));assert r[:2]==(0,'fifo/enqueued'),(i,r)
 assert run('enqueue',format(64,'064x'))[:2]==(1,'fifo/full')
 assert run('front')[:2]==(0,'fifo/front '+format(0,'064x'))
 assert run('dequeue',format(0,'064x'))[:2]==(0,'fifo/dequeued')
 assert run('enqueue',format(64,'064x'))[:2]==(0,'fifo/enqueued')
 print('CAPACITY_OK 64 full 1-dequeue 1-enqueue',flush=True)
finally:os.close(fd)
