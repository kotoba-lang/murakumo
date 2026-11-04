#!/usr/bin/env python3
"""Observe one FIFO/watcher failure boundary; not a host supervisor or governor."""
import argparse,ctypes,hashlib,os,resource,select,struct,subprocess,tempfile,time
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--fifo',type=Path,required=True);parser.add_argument('--fifo-sha256',required=True)
parser.add_argument('--watcher',type=Path,required=True);parser.add_argument('--watcher-sha256',required=True)
args=parser.parse_args();fifo=args.fifo.resolve(strict=True);watch=args.watcher.resolve(strict=True)
for path,pin in [(fifo,args.fifo_sha256),(watch,args.watcher_sha256)]:
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
  sec,usec=identity(child.pid);req=struct.pack('<4sIQQII',b'WP02',child.pid,sec,usec,12345,1)
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
 a,b='a'*64,'b'*64
 expect('enqueue',(0,'fifo/enqueued'),a);expect('enqueue',(0,'fifo/enqueued'),b);expect('reserve',(0,'fifo/reserved'),a)
 child=subprocess.Popen(['/bin/sleep','5']);watcher=None
 try:
  watcher,req=spawn_watcher(child);hb=wait_exact(watcher.stdout,40,3)
  if hb!=b'WH01'+struct.pack('<I',1)+req:raise RuntimeError('bad heartbeat')
  watcher.kill();assert watcher.wait(timeout=3)==-9
  if child.poll() is not None:raise RuntimeError('child unexpectedly exited')
  expect('front',(1,'fifo/reservation-held'));expect('dequeue',(1,'fifo/reservation-held'),a);expect('reserve',(1,'fifo/reservation-held'),b)
  child.kill();assert child.wait(timeout=3)==-9
  expect('front',(1,'fifo/reservation-held'))
  # Manual test-fixture reconciliation only after our direct child was reaped.
  expect('release',(0,'fifo/release-pending'),a);expect('dequeue',(0,'fifo/dequeued'),a);expect('front',(1,'fifo/release-pending'));expect('settle',(0,'fifo/settled'),a)
  expect('front',(0,'fifo/front '+b));print('PASS watcher killed, direct child alive/then reaped, reservation retained; test-only explicit reconciliation')
 finally:
  if watcher and watcher.poll() is None:watcher.kill();watcher.wait()
  if watcher:watcher.stdout.close();watcher.stderr.close()
  if child.poll() is None:child.kill();child.wait()
 os.close(3)
