#!/usr/bin/env python3
"""Test-only RGQ4 hold after killing an owned coordinator, not a supervisor."""
import argparse, ctypes, hashlib, json, os, resource, select, shutil, signal, struct, subprocess, tempfile, time
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--fifo',type=Path,required=True);p.add_argument('--fifo-sha256',required=True)
p.add_argument('--watcher',type=Path,required=True);p.add_argument('--watcher-sha256',required=True)
a=p.parse_args(); fifo=a.fifo.resolve(strict=True); watcher=a.watcher.resolve(strict=True)
for path,digest in ((fifo,a.fifo_sha256),(watcher,a.watcher_sha256)):
 if hashlib.sha256(path.read_bytes()).hexdigest()!=digest:raise RuntimeError('pin mismatch')
 subprocess.run(['/usr/bin/codesign','--verify','--strict',str(path)],check=True,capture_output=True)
lib=ctypes.CDLL('/usr/lib/libproc.dylib',use_errno=True);lib.proc_pidinfo.argtypes=[ctypes.c_int,ctypes.c_int,ctypes.c_uint64,ctypes.c_void_p,ctypes.c_int];lib.proc_pidinfo.restype=ctypes.c_int
def identity(pid):
 buf=ctypes.create_string_buffer(136);n=lib.proc_pidinfo(pid,3,0,buf,136)
 if n!=136:return None
 got=struct.unpack_from('<I',buf.raw,12)[0];sec,usec=struct.unpack_from('<QQ',buf.raw,120)
 return (sec,usec) if got==pid and sec>0 and usec<=999999 else None
def limit():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
def readexact(stream,n,timeout):
 data=b'';end=time.monotonic()+timeout
 while len(data)<n:
  left=end-time.monotonic()
  if left<=0 or not select.select([stream],[],[],left)[0]:raise TimeoutError('frame')
  b=os.read(stream.fileno(),n-len(data))
  if not b:raise RuntimeError('unexpected eof')
  data+=b
 return data
root=Path(tempfile.mkdtemp(prefix='awai151-coordinator-death-',dir='/private/tmp'));q=root/'q';q.mkdir();(q/'queue.lock').write_text('');(q/'queue').write_text('RGQ4\n')
fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY)
if fd!=3:os.dup2(fd,3)
def op(action,ticket=None):
 r=subprocess.run([str(fifo),'/q',action]+([ticket] if ticket else []),cwd=root,pass_fds=(3,),env={'PATH':'/nonexistent'},capture_output=True,timeout=8,preexec_fn=limit)
 if r.stderr:raise RuntimeError(r.stderr)
 return r.returncode,r.stdout.decode().strip()
def expect(action,result,ticket=None):
 got=op(action,ticket)
 if got!=result:raise RuntimeError((action,got,result))
first,second='a'*64,'b'*64
coord=None;child_pid=watch_pid=None;child_id=watch_id=None
try:
 expect('enqueue',(0,'fifo/enqueued'),first);expect('enqueue',(0,'fifo/enqueued'),second);expect('reserve',(0,'fifo/reserved'),first)
 rd,wr=os.pipe();coord=os.fork()
 if coord==0:
  os.close(rd)
  child=None;w=None
  try:
   child=subprocess.Popen(['/bin/sleep','8'],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
   ci=identity(child.pid)
   if ci is None:raise RuntimeError('missing child identity')
   request=struct.pack('<4sIQQII',b'WP04',child.pid,ci[0],ci[1],12345,1)
   request_path=root/'req';request_path.write_bytes(request);reqfd=os.open(request_path,os.O_RDONLY);os.dup2(reqfd,3)
   w=subprocess.Popen([str(watcher)],cwd=root,pass_fds=(3,),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,preexec_fn=limit)
   wi=identity(w.pid)
   if wi is None:raise RuntimeError('missing watcher identity')
   if readexact(w.stdout,40,4)!=b'WR01\0\0\0\0'+request or readexact(w.stdout,40,4)!=b'WH01'+struct.pack('<I',1)+request:raise RuntimeError('watch readiness')
   os.write(wr,(json.dumps({'child':child.pid,'child_id':ci,'watcher':w.pid,'watcher_id':wi})+'\n').encode())
   while True:signal.pause()
  except BaseException as e:
   for proc in (w,child):
    if proc is not None and proc.poll() is None:
     proc.kill();proc.wait(timeout=3)
   os.write(wr,('ERROR '+repr(e)+'\n').encode());os._exit(1)
 os.close(wr)
 if not select.select([rd],[],[],6)[0]:raise RuntimeError('coordinator readiness timeout')
 msg=os.read(rd,1024).decode();os.close(rd)
 if msg.startswith('ERROR'):raise RuntimeError(msg)
 data=json.loads(msg);child_pid=data['child'];child_id=tuple(data['child_id']);watch_pid=data['watcher'];watch_id=tuple(data['watcher_id'])
 if identity(child_pid)!=child_id or identity(watch_pid)!=watch_id:raise RuntimeError('identity changed before kill')
 os.kill(coord,signal.SIGKILL);pid,status=os.waitpid(coord,0);coord=None
 if not os.WIFSIGNALED(status) or os.WTERMSIG(status)!=signal.SIGKILL:raise RuntimeError('coordinator not killed')
 if identity(child_pid)!=child_id:raise RuntimeError('child not alive after coordinator death')
 expect('front',(1,'fifo/reservation-held'));expect('dequeue',(1,'fifo/reservation-held'),first);expect('reserve',(1,'fifo/reservation-held'),second)
 print('PASS coordinator killed; owned direct child alive; reservation blocks next',flush=True)
 deadline=time.monotonic()+12
 while time.monotonic()<deadline and (identity(child_pid)==child_id or identity(watch_pid)==watch_id):time.sleep(.1)
 if identity(child_pid)==child_id or identity(watch_pid)==watch_id:raise RuntimeError('owned orphan still present; retained '+str(root))
 expect('front',(1,'fifo/reservation-held'));expect('reserve',(1,'fifo/reservation-held'),second)
 print('PASS after direct child and watcher disappearance reservation remains held',flush=True)
 # Diagnostic-only reconciliation; disappearance alone is not a tree proof.
 expect('release',(0,'fifo/release-pending'),first);expect('dequeue',(0,'fifo/dequeued'),first);expect('settle',(0,'fifo/settled'),first);expect('front',(0,'fifo/front '+second))
 print('PASS explicit diagnostic-only reconciliation advances queue',flush=True)
finally:
 if coord is not None:
  try:os.kill(coord,signal.SIGKILL);os.waitpid(coord,0)
  except ProcessLookupError:pass
  if child_pid is None:
   # An interrupted pre-readiness coordinator may have spawned an unknown
   # sleep. Let its fixed lifetime elapse before removing task-owned files.
   time.sleep(9)
 if child_pid is not None or watch_pid is not None:
  deadline=time.monotonic()+12
  while time.monotonic()<deadline and ((child_pid is not None and identity(child_pid)==child_id) or (watch_pid is not None and identity(watch_pid)==watch_id)):time.sleep(.1)
 if (child_pid is not None and identity(child_pid)==child_id) or (watch_pid is not None and identity(watch_pid)==watch_id):
  print('UNKNOWN task processes remain; retain',root,flush=True)
 else:shutil.rmtree(root)
 os.close(3)
 if fd!=3:os.close(fd)
