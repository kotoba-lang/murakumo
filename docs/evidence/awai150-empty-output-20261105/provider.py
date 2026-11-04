#!/usr/bin/env python3
"""Test current jo-put against the pinned diagnostic Darwin FS2 provider."""
import base64,hashlib,importlib.util,os,re,resource,shutil,signal,subprocess,sys,tempfile
from pathlib import Path
sys.dont_write_bytecode=True
repo=Path('/Users/junkawasaki/github/workspaces/codex/awai150-verified-cron-input')
spec=importlib.util.spec_from_file_location('forms','/Users/junkawasaki/github/workspaces/codex/awai147-job-archive-cursor/scripts/native/check-cron-decisions.py');h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
base=Path('/private/tmp/awai-flint-clock-cross.kotoba').read_text()
def extract(path,name):
 text=path.read_text();a,b=h.form(text,name);return text[a:b]
checkpoint=repo/'kotoba/git_checkpoint.kotoba';output=repo/'kotoba/fleet_job_output.kotoba'
for name in ('cp-frame','cp-fs','cp-read-op','cp-cid','cp-range','cp-meta-field'):
 a,b=h.form(base,name)
 if base[a:b]!=extract(checkpoint,name):raise RuntimeError('historical fixture differs: '+name)
a,b=h.form(base,'cp-size');base=base[:a]+extract(checkpoint,'cp-size')+base[b:]
a,b=h.form(base,'main');base=base[:a]+base[b:]
base+='\n'+extract(checkpoint,'cp-empty-readable?')+'\n'+extract(output,'jo-put')+'\n'
base+=r'''(defn main [] :i64
 (let [mode (cp-arg 0) payload (if (= mode "nonempty") [7] (vector-alloc 0))
       expected (cp-cid payload) result (jo-put "/store" payload)]
  (if (= result (if (= mode "unreadable") "" expected)) 0 1)))'''
compiler=Path('/tmp/codex-amu-1762/seed/bootstrap/aarch64-macos/amu-full-root.macho');pack=Path('/private/tmp/awai154-fs2-darwin-gate/candidate.macho')
for p,w in ((compiler,'920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6'),(pack,'f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d')):
 if hashlib.sha256(p.read_bytes()).hexdigest()!=w:raise RuntimeError('pin')
def cid(data):return 'b'+base64.b32encode(b'\x01\x55\x12\x20'+hashlib.sha256(data).digest()).decode().rstrip('=').lower()
with tempfile.TemporaryDirectory(prefix='awai150-empty-provider-',dir='/private/tmp') as d:
 root=Path(d);fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY)
 if fd!=3:os.dup2(fd,3)
 def run(args,expected=0):
  p=subprocess.run([str(x) for x in args],cwd=root,pass_fds=(3,),env={'PATH':'/nonexistent'},capture_output=True,timeout=12,preexec_fn=lambda:resource.setrlimit(resource.RLIMIT_CPU,(3,3)))
  if p.returncode!=expected or p.stderr:raise RuntimeError((args,p.returncode,p.stdout[-500:],p.stderr[-500:]))
  return p.stdout
 (root/'f.kotoba').write_text(base);run([compiler,'compile','f.kotoba','--target','aarch64-macos','--output','f.kseed']);out=run([compiler,'extract-native','f.kseed','--symbol','main','--output','f.raw']);m=re.search(rb':offset (\d+), :length (\d+), :arity 0}',out)
 if not m or int(m[2])!=(root/'f.raw').stat().st_size:raise RuntimeError('extract')
 run([pack,'unsigned.macho','f.raw','fs2-cli-stdio-general',m[1].decode()]);img=root/'f.macho';shutil.copyfile(root/'unsigned.macho',img);img.chmod(0o700);subprocess.run(['/usr/bin/codesign','--force','--sign','-',str(img)],check=True,capture_output=True)
 store=root/'store';store.mkdir();empty=store/cid(b'');empty.write_bytes(b'');nonempty=store/cid(bytes([7]));nonempty.write_bytes(bytes([7]))
 for mode in ('readable','nonempty','unreadable'):
  empty.chmod(0 if mode=='unreadable' else 0o600)
  try:
   # This diagnostic provider traps on open failure instead of returning an
   # error frame. A host must treat that interruption as unknown, not success.
   run([img,mode],-signal.SIGTRAP if mode=='unreadable' else 0)
   print('PASS provider existing block',mode,'trap/unknown' if mode=='unreadable' else 'verified',flush=True)
  finally:empty.chmod(0o600)
 print('SOURCE_SHA',hashlib.sha256(base.encode()).hexdigest(),flush=True)
 os.close(3)
 if fd!=3:os.close(fd)
