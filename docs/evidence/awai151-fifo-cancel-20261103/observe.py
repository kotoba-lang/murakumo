# One-off diagnostic observer; synthetic queue only.
import json,os,pathlib,subprocess
p=pathlib.Path('/private/tmp/awai151-fifo-cancel'); root=p/'case';root.mkdir();(root/'queue.lock').write_text('');(root/'queue').write_text('RGQ1\n')
fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
A='a'*64;B='b'*64;C='c'*64;D='d'*64
rows=[]
def run(op,ticket=None):
 args=[str(p/'fifo.macho'),'/case',op]+([ticket] if ticket else [])
 r=subprocess.run(args,cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=8)
 result=(r.returncode,r.stdout.strip());rows.append([op,result]);return result
def queue(*items):return 'RGQ1\n'+''.join(x+'\n' for x in items)
def journal(old,new):return 'RGI1\n'+old+'NEXT\n'+new
try:
 for x in (A,B,C,D):assert run('enqueue',x)==(0,'fifo/enqueued')
 assert run('cancel',A)==(1,'fifo/cancel-front-refused')
 assert run('cancel','e'*64)==(1,'fifo/not-queued')
 assert run('cancel',C)==(0,'fifo/cancelled')
 assert (root/'queue').read_text()==queue(A,B,D)
 assert run('front')==(0,'fifo/front '+A)
 assert run('dequeue',A)==(0,'fifo/dequeued')
 assert run('front')==(0,'fifo/front '+B)
 assert run('cancel',D)==(0,'fifo/cancelled')
 assert (root/'queue').read_text()==queue(B)
 # Emulate a crash after queue rename: explicit recovery archives the intent.
 old=queue(A,B,C);new=queue(A,C)
 (root/'queue').write_text(new);(root/'queue.intent').write_text(journal(old,new))
 assert run('front')==(1,'fifo/pending-unknown')
 assert run('recover')==(0,'fifo/recovered')
 assert (root/'queue').read_text()==new
 # Two removals or reordering must not become recoverable.
 for new in (queue(A),queue(C,B)):
  (root/'queue').write_text(new);(root/'queue.intent').write_text(journal(old,new))
  assert run('recover')==(1,'fifo/journal-invalid')
  assert (root/'queue').read_text()==new
  (root/'queue.intent').unlink()
 print(json.dumps({'checks':rows,'result':'pass'}))
finally:os.close(fd)
