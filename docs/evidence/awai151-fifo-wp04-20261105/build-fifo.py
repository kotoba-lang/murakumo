import hashlib,os,re,resource,shutil,subprocess,tempfile
from pathlib import Path
repo=Path('/Users/junkawasaki/github/workspaces/codex/awai151-bounded-fifo');compiler=Path('/tmp/codex-amu-1762/seed/bootstrap/aarch64-macos/amu-full-root.macho');packager=Path('/private/tmp/awai154-fs2-darwin-gate/candidate.macho');sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
for p,pin in [(compiler,'920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6'),(packager,'f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d')]:
 if sha(p)!=pin:raise ValueError('pin mismatch')
src=(repo/'kotoba/resource_fifo.kotoba').read_text();src='(ns fifo-current (:export [main]))\n'+src[src.index('(defn- rq-arg'):]+ '\n(defn main [] :i64 (resource-fifo-main))\n'
with tempfile.TemporaryDirectory(prefix='awai151-current-build-',dir='/private/tmp') as d:
 root=Path(d);fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY)
 if fd!=3:os.dup2(fd,3)
 def run(cmd):
  r=subprocess.run([str(x) for x in cmd],cwd=root,pass_fds=(3,),capture_output=True,timeout=15,env={'PATH':'/nonexistent'},preexec_fn=lambda:resource.setrlimit(resource.RLIMIT_CPU,(3,3)))
  if r.returncode or r.stderr:raise RuntimeError((r.returncode,r.stdout[-300:],r.stderr[-300:]))
  return r.stdout
 (root/'fifo.kotoba').write_text(src);run([compiler,'compile','fifo.kotoba','--target','aarch64-macos','--output','fifo.kseed']);out=run([compiler,'extract-native','fifo.kseed','--symbol','main','--output','fifo.raw']);m=re.search(rb':offset (\d+), :length (\d+), :arity 0}',out)
 if not m or int(m[2])!=(root/'fifo.raw').stat().st_size:raise ValueError('extract')
 run([packager,'fifo-unsigned.macho','fifo.raw','fs2-cli-stdio-general',m[1].decode()]);img=root/'fifo.macho';shutil.copyfile(root/'fifo-unsigned.macho',img);img.chmod(0o700);subprocess.run(['/usr/bin/codesign','--force','--sign','-',str(img)],check=True,capture_output=True);subprocess.run(['/usr/bin/codesign','--verify','--strict',str(img)],check=True,capture_output=True)
 dest=Path('/private/tmp/awai151-current-fifo.macho');
 if dest.exists():raise RuntimeError('exists')
 shutil.copyfile(img,dest);dest.chmod(0o700);print('component',sha(repo/'kotoba/resource_fifo.kotoba'),'unity',hashlib.sha256(src.encode()).hexdigest(),'image',sha(dest))
