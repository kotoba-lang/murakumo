#!/usr/bin/env python3
"""Observe one FIFO/watcher failure boundary; not a host supervisor or governor."""
import argparse,ctypes,hashlib,os,resource,select,signal,struct,subprocess,tempfile,time
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--fifo',type=Path,required=True);parser.add_argument('--fifo-sha256',required=True)
parser.add_argument('--watcher',type=Path,required=True);parser.add_argument('--watcher-sha256',required=True)
parser.add_argument('--launcher',type=Path,required=True);parser.add_argument('--launcher-sha256',required=True)
args=parser.parse_args();fifo=args.fifo.resolve(strict=True);watch=args.watcher.resolve(strict=True);launcher=args.launcher.resolve(strict=True)
for path,pin in [(fifo,args.fifo_sha256),(watch,args.watcher_sha256),(launcher,args.launcher_sha256)]:
 if hashlib.sha256(path.read_bytes()).hexdigest()!=pin:raise ValueError('pin mismatch')
 subprocess.run(['/usr/bin/codesign','--verify','--strict',str(path)],check=True,capture_output=True)
lib=ctypes.CDLL('/usr/lib/libproc.dylib',use_errno=True);lib.proc_pidinfo.argtypes=[ctypes.c_int,ctypes.c_int,ctypes.c_uint64,ctypes.c_void_p,ctypes.c_int];lib.proc_pidinfo.restype=ctypes.c_int
def limit():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
def identity(pid):
 raw=ctypes.create_string_buffer(136)
 if lib.proc_pidinfo(pid,3,0,raw,136)!=136:raise RuntimeError('identity unavailable')
 observed=struct.unpack_from('<I',raw.raw,12)[0];sec,usec=struct.unpack_from('<QQ',raw.raw,120)
 if observed!=pid or not sec or usec>999999:raise RuntimeError('identity invalid')
 return sec,usec
def wait_exact(f,n,timeout):
 out=b'';deadline=time.monotonic()+timeout
 while len(out)<n:
  remaining=deadline-time.monotonic()
  if remaining<=0 or not select.select([f],[],[],remaining)[0]:raise TimeoutError('watch frame')
  chunk=os.read(f.fileno(),n-len(out))
  if not chunk:raise RuntimeError('watch EOF')
  out+=chunk
 return out
with tempfile.TemporaryDirectory(prefix='awai151-watch-fifo-',dir='/private/tmp') as d:
 root=Path(d);q=root/'q';q.mkdir();(q/'queue.lock').write_text('');(q/'queue').write_text('RGQ4\n')
 fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY)
 if fd!=3:os.dup2(fd,3)
 def op(action,ticket=None):
  r=subprocess.run([str(fifo),'/q',action]+([ticket] if ticket else []),cwd=root,pass_fds=(3,),env={'PATH':'/nonexistent'},capture_output=True,timeout=10,preexec_fn=limit)
  if r.stderr:raise RuntimeError(r.stderr)
  return r.returncode,r.stdout.decode().strip()
 def expect(action,result,ticket=None):
  observed=op(action,ticket)
  if observed!=result:raise RuntimeError((action,observed,result))
 def spawn_watcher(child):
  sec,usec=identity(child.pid);req=struct.pack('<4sIQQII',b'WP04',child.pid,sec,usec,12345,1)
  p=root/'req';p.write_bytes(req);request_fd=os.open(p,os.O_RDONLY);saved=os.dup(3);os.dup2(request_fd,3)
  try: w=subprocess.Popen([str(watch)],cwd=root,pass_fds=(3,),stdout=subprocess.PIPE,stderr=subprocess.PIPE,preexec_fn=limit)
  finally:os.dup2(saved,3);os.close(saved);os.close(request_fd)
  try:
   if wait_exact(w.stdout,40,4)!=b'WR01\0\0\0\0'+req:raise RuntimeError('bad readiness')
  except BaseException:
   if w.poll() is None:w.kill();w.wait()
   w.stdout.close();w.stderr.close()
   raise
  return w,req
 for scenario in ('native-kapitsa-version',):
  for entry in q.iterdir():
   if entry.is_dir():raise RuntimeError('unexpected nested entry')
   entry.unlink()
  (q/'queue.lock').write_text('');(q/'queue').write_text('RGQ4\n')
  a,b='a'*64,'b'*64
  expect('enqueue',(0,'fifo/enqueued'),a);expect('enqueue',(0,'fifo/enqueued'),b);expect('reserve',(0,'fifo/reserved'),a)
  target=Path.home()/'.local/share/awai/flint16-0.2.26/kapitsa-0.2.26-darwin-arm64/kapitsa'
  if hashlib.sha256(target.read_bytes()).hexdigest()!='20db8dadcb79de820d237367ca93d7ac66f8a70d91d5513f6e1ab77253925daa':raise RuntimeError('target pin')
  subprocess.run(['/usr/bin/codesign','--verify','--strict',str(target)],check=True,capture_output=True,timeout=8)
  child=subprocess.Popen(['/usr/bin/sandbox-exec','-p','(version 1) (allow default) (deny process-fork)',str(launcher)],stdout=subprocess.PIPE,stderr=subprocess.PIPE);watcher=None
  try:
   deadline=time.monotonic()+8
   while True:
    got,st=os.waitpid(child.pid,os.WNOHANG|os.WUNTRACED)
    if got:
     if not os.WIFSTOPPED(st):raise RuntimeError(('launcher not stopped',st))
     break
    if time.monotonic()>deadline:raise TimeoutError('launcher did not stop')
    time.sleep(.01)
   ps=subprocess.run(['/bin/ps','-p',str(child.pid),'-o','comm='],capture_output=True,text=True,timeout=3)
   if ps.returncode or ps.stdout.strip()!=str(target):raise RuntimeError('stopped target mismatch')
   watcher,req=spawn_watcher(child);hb=wait_exact(watcher.stdout,40,4)
   if hb!=b'WH01'+struct.pack('<I',1)+req:raise RuntimeError('bad heartbeat')
   expect('front',(1,'fifo/reservation-held'));expect('dequeue',(1,'fifo/reservation-held'),a);expect('reserve',(1,'fifo/reservation-held'),b)
   os.kill(child.pid,signal.SIGCONT)
   if scenario=='watcher-lost':
    watcher.kill();assert watcher.wait(timeout=3)==-9
    if child.poll() is not None:raise RuntimeError('child unexpectedly exited')
    expect('front',(1,'fifo/reservation-held'))
    child.kill();assert child.wait(timeout=3)==-9
   else:
    if scenario=='child-killed':child.kill()
    frame=wait_exact(watcher.stdout,40,4);sequence=2
    while frame[:4]==b'WH01':
     if frame!=b'WH01'+struct.pack('<I',sequence)+req:raise RuntimeError('bad later heartbeat')
     sequence+=1;frame=wait_exact(watcher.stdout,40,4)
    status=9 if scenario=='child-killed' else 0
    if frame!=b'WS01'+struct.pack('<I',status)+req:raise RuntimeError(('bad status',scenario,frame))
    if watcher.wait(timeout=3)!=0 or watcher.stdout.read() or watcher.stderr.read():raise RuntimeError('watcher completion unknown')
    exitcode=child.wait(timeout=3)
    if exitcode!=(-9 if scenario=='child-killed' else 0):raise RuntimeError(('child mismatch',exitcode))
    if child.stdout.read()!=b'kapitsa 0.2.26 (6ef19b0)\n' or child.stderr.read():raise RuntimeError('version output mismatch')
   # Even a matching exit frame is not permission to release the reservation.
   expect('front',(1,'fifo/reservation-held'));expect('reserve',(1,'fifo/reservation-held'),b)
   # Test-only reconciliation after reaping the owned direct child; not a tree proof.
   expect('release',(0,'fifo/release-pending'),a);expect('dequeue',(0,'fifo/dequeued'),a);expect('front',(1,'fifo/release-pending'));expect('settle',(0,'fifo/settled'),a)
   expect('front',(0,'fifo/front '+b));print('PASS',scenario,'hold preserved until explicit test-only reconciliation',flush=True)
  finally:
   if watcher and watcher.poll() is None:watcher.kill();watcher.wait()
   if watcher:watcher.stdout.close();watcher.stderr.close()
   if child.poll() is None:child.kill();child.wait()
 os.close(3)
