# Test only: pipe-blocked fixtures and SIGKILL of their own processes.
import json,os,pathlib,resource,select,subprocess
p=pathlib.Path('/private/tmp/awai151-mac-fifo-kill'); normal='/private/tmp/awai151-mac-fifo-v4/fifo.macho';ticket='a'*64
fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
def limits():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
try:
 for phase in ('after-intent','after-pending','after-rename','after-sync','after-archive'):
  root=p/('case-'+phase);root.mkdir();(root/'queue').write_text('RGQ1\n');(root/'queue.lock').write_text('')
  c=subprocess.Popen([str(p/(phase+'.macho')),'/case-'+phase,'enqueue',ticket],cwd=p,pass_fds=(fd,),stdout=subprocess.PIPE,stderr=subprocess.PIPE,preexec_fn=limits)
  try:
   ready,_,_=select.select([c.stdout],[],[],5)
   if not ready:raise RuntimeError('no pause reached: '+phase+' exit='+str(c.poll()))
   assert os.read(c.stdout.fileno(),1)==b'\0' and c.poll() is None
   intent=(root/'queue.intent').exists();pending=(root/'queue.pending').exists();state=(root/'queue').read_text()
   assert intent==(phase!='after-archive'),(phase,intent)
   assert state==('RGQ1\n'+ticket+'\n' if phase in ('after-rename','after-sync','after-archive') else 'RGQ1\n')
   c.kill();c.wait(timeout=3)
   r=subprocess.run([normal,'/case-'+phase,'front'],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5)
   expected=(0,'fifo/front '+ticket) if phase=='after-archive' else (1,'fifo/pending-unknown')
   assert (r.returncode,r.stdout.strip())==expected,(phase,r.returncode,r.stdout,r.stderr)
   print(json.dumps({'phase':phase,'killedExit':c.returncode,'intent':intent,'pending':pending,'front':r.stdout.strip(),'frontExit':r.returncode}),flush=True)
  finally:
   if c.poll() is None:c.kill();c.wait(timeout=3)
finally:os.close(fd)
