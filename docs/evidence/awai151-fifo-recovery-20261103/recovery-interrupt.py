import os,pathlib,resource,select,subprocess
p=pathlib.Path('/private/tmp/awai151-fifo-recovery-interrupt'); fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY); assert fd==3
ticket='a'*64; old='RGQ1\n'; new=old+ticket+'\n'
def limits():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
def run(root,op):
 r=subprocess.run([str(p/'fifo.macho'),'/'+root.name,op],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5);return r.returncode,r.stdout.strip()
try:
 for phase in ('before-archive','after-archive'):
  root=p/('kill-'+phase);root.mkdir();(root/'queue.lock').write_text('');(root/'queue').write_text(new);(root/'queue.intent').write_text('RGI1\n'+old+'NEXT\n'+new)
  c=subprocess.Popen([str(p/(phase+'.macho')),'/'+root.name,'recover'],cwd=p,pass_fds=(fd,),stdout=subprocess.PIPE,stderr=subprocess.PIPE,preexec_fn=limits)
  try:
   ready,_,_=select.select([c.stdout],[],[],5);assert ready and os.read(c.stdout.fileno(),1)==b'\0' and c.poll() is None
   c.kill();c.wait(timeout=3);assert c.returncode==-9
   result=run(root,'recover');expected=(0,'fifo/recovered') if phase=='before-archive' else (1,'fifo/no-intent')
   assert result==expected,(phase,result)
   assert run(root,'front')==(0,'fifo/front '+ticket)
   print(phase,result,flush=True)
  finally:
   if c.poll() is None:c.kill();c.wait(timeout=3)
finally:os.close(fd)
