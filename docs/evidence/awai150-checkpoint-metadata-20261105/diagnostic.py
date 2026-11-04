#!/usr/bin/env python3
"""Run selected cp-size and malformed metadata controls in Darwin native code."""
import hashlib,importlib.util,os,re,resource,shutil,subprocess,sys,tempfile
from pathlib import Path
sys.dont_write_bytecode=True
repo=Path('/Users/junkawasaki/github/workspaces/codex/awai150-verified-cron-input')
spec=importlib.util.spec_from_file_location('forms','/Users/junkawasaki/github/workspaces/codex/awai147-job-archive-cursor/scripts/native/check-cron-decisions.py');h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
src=(repo/'kotoba/git_checkpoint.kotoba').read_text()
def form(name):a,b=h.form(src,name);return src[a:b]
pre=r'''(ns checkpoint-metadata-fixture (:export [main]))
(defn- cp-fs [op :string path :string tail :string] :string
 (let [m (typed-cap-call 38 :string :string "0")]
  (cond (= m "valid") "384 12 1 0" (= m "empty") "384 0 0 0" (= m "wide-blocks") "384 12 12345678901234567890 0" (= m "bad-mode") "oops 12 1 0"
        (= m "mode-range") "512 12 1 0" (= m "bad-blocks") "384 12 bad 0"
        (= m "extra") "384 12 1 0 extra" (= m "missing") "384 12 0"
        (= m "bad-size") "384 1x 1 0" (= m "wide-overflow") "384 12 123456789012345678901 0" (= m "directory") "448 12 1 1" :else "")))
'''
main=r'''(defn main [] :i64
 (let [m (typed-cap-call 38 :string :string "0") expected (cond (or (= m "valid") (= m "wide-blocks")) 12 (= m "empty") 0 :else -1)]
  (if (= (cp-size "/x") expected) 0 1)))'''
source=pre+'\n'.join(form(n) for n in ('cp-bytes','cp-int','cp-meta-field','cp-meta-digits?','cp-meta-size','cp-size'))+'\n'+main
compiler=Path('/tmp/codex-amu-1762/seed/bootstrap/aarch64-macos/amu-full-root.macho');pack=Path('/private/tmp/awai154-fs2-darwin-gate/candidate.macho')
for p,pin in ((compiler,'920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6'),(pack,'f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d')):
 if hashlib.sha256(p.read_bytes()).hexdigest()!=pin:raise RuntimeError('pin mismatch')
with tempfile.TemporaryDirectory(prefix='awai150-meta-',dir='/private/tmp') as d:
 root=Path(d);fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY)
 if fd!=3:os.dup2(fd,3)
 def run(cmd,expected=0):
  r=subprocess.run([str(x) for x in cmd],cwd=root,pass_fds=(3,),env={'PATH':'/nonexistent'},capture_output=True,timeout=12,preexec_fn=lambda:resource.setrlimit(resource.RLIMIT_CPU,(3,3)))
  if r.returncode!=expected or r.stderr:raise RuntimeError((cmd,r.returncode,r.stdout[-500:],r.stderr[-500:]))
  return r.stdout
 def build(name,content):
  (root/(name+'.kotoba')).write_text(content);run([compiler,'compile',name+'.kotoba','--target','aarch64-macos','--output',name+'.kseed']);out=run([compiler,'extract-native',name+'.kseed','--symbol','main','--output',name+'.raw']);m=re.search(rb':offset (\d+), :length (\d+), :arity 0}',out)
  if not m or int(m[2])!=(root/(name+'.raw')).stat().st_size:raise RuntimeError('extract')
  run([pack,name+'-unsigned.macho',name+'.raw','fs2-cli-stdio-general',m[1].decode()]);img=root/(name+'.macho');shutil.copyfile(root/(name+'-unsigned.macho'),img);img.chmod(0o700);subprocess.run(['/usr/bin/codesign','--force','--sign','-',str(img)],check=True,capture_output=True);return img
 img=build('fixture',source)
 for mode in ('valid','empty','wide-blocks','wide-overflow','bad-mode','mode-range','bad-blocks','extra','missing','bad-size','directory'):
  run([img,mode]);print('PASS metadata',mode,flush=True)
 old='(defn- cp-size [p :string] :i64 (let [m (cp-fs "O" p "")] (if (= (cp-meta-field m 3) "0") (cp-int (cp-meta-field m 1)) -1)))'
 start,end=h.form(source,'cp-size');mutant=build('mutant',source[:start]+old+source[end:])
 for mode in ('bad-mode','mode-range','bad-blocks','extra'):
  run([mutant,mode],1);print('PASS metadata old-parser mutant detected',mode,flush=True)
 print('SOURCE_SHA',hashlib.sha256(source.encode()).hexdigest(),flush=True)
 os.close(3)
 if fd!=3:os.close(fd)
