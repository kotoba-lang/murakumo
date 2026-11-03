import os,pathlib,select,subprocess
p=pathlib.Path('/private/tmp/awai151-fifo-abort-final.c76l1xI5');d=p/'killed';d.mkdir();A='a'*64;B='b'*64;C='c'*64;q=lambda *x:'RGQ1\n'+''.join(t+'\n' for t in x)
(d/'queue.lock').touch();(d/'queue').write_text(q(A,B,C));fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
try:
 c=subprocess.Popen([str(p/'instrumented.macho'),'/killed','cancel',B],cwd=p,pass_fds=(fd,),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 try:
  ready,_,_=select.select([c.stdout],[],[],5);assert ready and os.read(c.stdout.fileno(),1)==b'\0' and c.poll() is None;c.kill();c.wait(timeout=3);assert c.returncode==-9
 finally:
  if c.poll() is None:c.kill();c.wait(timeout=3)
 assert (d/'queue').read_text()==q(A,B,C) and (d/'queue.intent').exists() and not (d/'queue.pending').exists()
 def run(op,*args):
  r=subprocess.run([str(p/'fifo.macho'),'/killed',op,*args],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5);print(op,r.returncode,r.stdout.strip(),flush=True);return r.returncode,r.stdout.strip()
 assert run('front')==(1,'fifo/pending-unknown')
 assert run('recover')==(0,'fifo/aborted')
 assert (d/'queue').read_text()==q(A,B,C) and not (d/'queue.intent').exists()
 assert run('dequeue',A)==(0,'fifo/dequeued') and run('front')==(0,'fifo/front '+B)
 print('crash test passed')
finally:os.close(fd)
