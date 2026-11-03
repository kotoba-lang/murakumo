# Diagnostic only: SIGKILL exclusively our own short-lived test processes.
import json,os,pathlib,resource,select,subprocess
p=pathlib.Path('/private/tmp/awai151-cancel-crash');fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
A='a'*64; B='b'*64; C='c'*64
q=lambda *xs:'RGQ1\n'+''.join(x+'\n' for x in xs)
rows=[]
def limits():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
def run(root,op,ticket=None):
 args=[str(p/'fifo.macho'),'/'+root.name,op]+([ticket] if ticket else [])
 r=subprocess.run(args,cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5)
 return (r.returncode,r.stdout.strip())
try:
 for phase in ('after-intent','after-pending','after-rename','after-sync','after-archive'):
  root=p/phase;root.mkdir();(root/'queue.lock').write_text('');(root/'queue').write_text(q(A,B,C))
  c=subprocess.Popen([str(p/(phase+'.macho')),'/'+root.name,'cancel',B],cwd=p,pass_fds=(fd,),stdout=subprocess.PIPE,stderr=subprocess.PIPE,preexec_fn=limits)
  try:
   ready,_,_=select.select([c.stdout],[],[],5);assert ready and os.read(c.stdout.fileno(),1)==b'\0' and c.poll() is None,phase
   c.kill();c.wait(timeout=3);assert c.returncode==-9
   front=run(root,'front');rec=run(root,'recover')
   if phase in ('after-intent','after-pending'):
    assert front==(1,'fifo/pending-unknown') and rec==(1,'fifo/recovery-unresolved')
    assert (root/'queue').read_text()==q(A,B,C) and (root/'queue.intent').exists()
   elif phase in ('after-rename','after-sync'):
    assert front==(1,'fifo/pending-unknown') and rec==(0,'fifo/recovered')
    assert (root/'queue').read_text()==q(A,C) and not (root/'queue.intent').exists()
    assert run(root,'recover')==(1,'fifo/no-intent')
   else:
    assert front==(0,'fifo/front '+A) and rec==(1,'fifo/no-intent')
    assert (root/'queue').read_text()==q(A,C)
   if phase not in ('after-intent','after-pending'):
    assert run(root,'dequeue',A)==(0,'fifo/dequeued') and run(root,'front')==(0,'fifo/front '+C)
   rows.append({'phase':phase,'front':front,'recovery':rec})
  finally:
   if c.poll() is None:c.kill();c.wait(timeout=3)
 print(json.dumps(rows),flush=True)
finally:os.close(fd)
