import os,pathlib,subprocess
p=pathlib.Path('/private/tmp/awai151-fifo-abort-final.c76l1xI5');fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
A='a'*64;B='b'*64;C='c'*64
q=lambda *t:'RGQ1\n'+''.join(x+'\n' for x in t)
def check(name,old,new,state,expected,pending=False):
 d=p/name;d.mkdir();(d/'queue.lock').touch();(d/'queue').write_text(state);j='RGI1\n'+old+'NEXT\n'+new;(d/'queue.intent').write_text(j)
 if pending:(d/'queue.pending').write_text(new)
 def run(op):
  r=subprocess.run([str(p/'fifo.macho'),'/'+name,op],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5);return r.returncode,r.stdout.strip()
 got=run('recover');print(name,got,flush=True);assert got==expected;assert (d/'queue').read_text()==state
 if expected[0]==0:
  assert not (d/'queue.intent').exists() and (d/'queue.last').read_text()==j
  assert run('recover')==(1,'fifo/no-intent')
  assert run('front')==((0,'fifo/front '+state[5:69]) if state!='RGQ1\n' else (0,'fifo/empty'))
 else:
  assert (d/'queue.intent').read_text()==j
  if pending:assert (d/'queue.pending').read_text()==new
try:
 check('append-old',q(A),q(A,B),q(A),(0,'fifo/aborted'))
 check('remove-old',q(A,B),q(B),q(A,B),(0,'fifo/aborted'))
 check('cancel-old',q(A,B,C),q(A,C),q(A,B,C),(0,'fifo/aborted'))
 check('append-new',q(A),q(A,B),q(A,B),(0,'fifo/recovered'))
 check('unrelated',q(A),q(A,B),q(C),(1,'fifo/recovery-unresolved'))
 check('pending',q(A),q(A,B),q(A),(1,'fifo/recovery-unresolved'),True)
 check('invalid',q(A,B),q(B,A),q(A,B),(1,'fifo/journal-invalid'))
 print('all tests passed')
finally:os.close(fd)
