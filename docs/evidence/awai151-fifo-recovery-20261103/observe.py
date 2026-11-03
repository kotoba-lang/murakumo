# Test only: isolated synthetic queue states and owned child SIGKILL.
import json,os,pathlib,resource,select,subprocess
p=pathlib.Path('/private/tmp/awai151-fifo-recovery'); normal=p/'fifo.macho';ticket='a'*64
fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
rows=[]
def limits():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
def run(root,op):
 r=subprocess.run([str(normal),'/'+root.name,op],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5)
 return r.returncode,r.stdout.strip()
def init(name):
 root=p/name;root.mkdir();(root/'queue').write_text('RGQ1\n');(root/'queue.lock').write_text('');return root
try:
 for phase in ('after-intent','after-pending','after-rename','after-sync','after-archive'):
  root=init('kill-'+phase)
  c=subprocess.Popen([str(p/(phase+'.macho')),'/'+root.name,'enqueue',ticket],cwd=p,pass_fds=(fd,),stdout=subprocess.PIPE,stderr=subprocess.PIPE,preexec_fn=limits)
  try:
   ready,_,_=select.select([c.stdout],[],[],5);assert ready and os.read(c.stdout.fileno(),1)==b'\0' and c.poll() is None,phase
   c.kill();c.wait(timeout=3); assert c.returncode==-9
   front=run(root,'front'); rec=run(root,'recover')
   if phase in ('after-intent','after-pending'):
    assert front==(1,'fifo/pending-unknown') and rec==(1,'fifo/recovery-unresolved'),(phase,front,rec)
    assert (root/'queue.intent').exists()
   elif phase in ('after-rename','after-sync'):
    assert front==(1,'fifo/pending-unknown') and rec==(0,'fifo/recovered'),(phase,front,rec)
    assert run(root,'front')==(0,'fifo/front '+ticket) and not (root/'queue.intent').exists()
    assert run(root,'recover')==(1,'fifo/no-intent')
   else:
    assert front==(0,'fifo/front '+ticket) and rec==(1,'fifo/no-intent'),(phase,front,rec)
   rows.append({'phase':phase,'front':front,'recover':rec})
  finally:
   if c.poll() is None:c.kill();c.wait(timeout=3)
 old='RGQ1\n';new=old+ticket+'\n'
 for name,journal,state,pending,expected in [
   ('bad-magic','bad',new,False,'fifo/journal-invalid'),
   ('bad-transition','RGI1\n'+old+'NEXT\n'+old,old,False,'fifo/journal-invalid'),
   ('state-mismatch','RGI1\n'+old+'NEXT\n'+new,old,False,'fifo/recovery-unresolved'),
   ('pending-present','RGI1\n'+old+'NEXT\n'+new,new,True,'fifo/recovery-unresolved')]:
  root=init(name);(root/'queue').write_text(state);(root/'queue.intent').write_text(journal)
  if pending:(root/'queue.pending').write_text(new)
  result=run(root,'recover');assert result==(1,expected),(name,result)
  assert (root/'queue.intent').read_text()==journal and (root/'queue').read_text()==state
  rows.append({'case':name,'recover':result})
 print(json.dumps(rows),flush=True)
finally:os.close(fd)
