# Synthetic journal checks using the uninstrumented diagnostic artifact.
import os,pathlib,subprocess
p=pathlib.Path('/private/tmp/awai151-fifo-recovery'); fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
A='a'*64;B='b'*64;C='c'*64
q=lambda *xs:'RGQ1\n'+''.join(x+'\n' for x in xs)
def check(name,old,new,state,expected,front=None):
 root=p/name;root.mkdir();(root/'queue.lock').write_text('');(root/'queue').write_text(state)
 journal='RGI1\n'+old+'NEXT\n'+new;(root/'queue.intent').write_text(journal)
 def run(op):
  r=subprocess.run([str(p/'fifo.macho'),'/'+root.name,op],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5)
  return (r.returncode,r.stdout.strip())
 got=run('recover');assert got==expected,(name,got)
 assert (root/'queue').read_text()==state
 if front is not None:
  assert run('front')==(0,'fifo/front '+front) and (root/'queue.last').read_text()==journal
 else: assert (root/'queue.intent').read_text()==journal
 print(name,got)
try:
 check('dequeue-recovery',q(A,B),q(B),q(B),(0,'fifo/recovered'),B)
 check('nonhead-removal',q(A,B),q(A),q(A),(1,'fifo/journal-invalid'))
 check('replace-head',q(A,B),q(C,B),q(C,B),(1,'fifo/journal-invalid'))
 check('duplicate-append',q(A),q(A,A),q(A),(1,'fifo/journal-invalid'))
 check('two-removals',q(A,B,C),q(C),q(C),(1,'fifo/journal-invalid'))
finally: os.close(fd)
