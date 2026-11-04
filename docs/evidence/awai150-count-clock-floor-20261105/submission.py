import hashlib, importlib.util, os, re, resource, shutil, subprocess, tempfile
from pathlib import Path
repo=Path('/Users/junkawasaki/github/workspaces/codex/awai-flint-response-contract')
spec=importlib.util.spec_from_file_location('forms','/Users/junkawasaki/github/workspaces/codex/awai147-job-archive-cursor/scripts/native/check-cron-decisions.py')
h=importlib.util.module_from_spec(spec); spec.loader.exec_module(h)
src=(repo/'kotoba/profile_recovery.kotoba').read_text()
def form(name):
 a,b=h.form(src,name); return src[a:b]
pre=r'''(ns count-submission-fixture (:export [main]))
(defn- mark [p :string] :bool (let [x (typed-cap-call 35 :bytes :bytes (string-to-utf8 (str "\u0000FS2\u0000B/" p "\u0000x")))] true))
(defn- pr-local-flint? [r :vector-i64] :bool true)
(defn- pr-count-id [r :vector-i64 b :vector-i64] :string "01234567890123456789012345678901")
(defn- cp-fs [a :string b :string c :string] :string "0")
(defn- cp-cid [b :vector-i64] :string "cid")
(defn- cp-text [b :vector-i64] :string "")
(defn- pr-read-bounded [p :string n :i64] :vector-i64 [1])
(defn- pr-unit-state [u :string] :string "absent")
(defn- pr-run-decision [s :string a :bool b :bool c :bool d :bool e :bool] :string "profile/new-unit")
(defn- pr-prepare-submit [r :string m :string b :vector-i64] :bool true)
(defn- pr-wall-ms [] :i64 100000)
(defn- pr-submit-kind [u :string r :string p :string s :i64 runtime :vector-i64 c :bool] :bool (mark "submitted"))
(defn- pr-count-wait [u :string p :string s :i64 d :i64 n :i64 clock :vector-i64] :i64 (if (= (vector-at clock 0) 100000) 20 -1))
'''
entry=r'''(defn main [] :i64
 (let [mode (typed-cap-call 38 :string :string "0") rollback (= mode "rollback") clock [(if rollback 101000 100000)]
       result (pr-count [1] "/cache" 10 [1] 99000 110000 false clock)
       submitted (= (typed-cap-call 35 :string :string "\u0000FS2\u0000D/submitted\u0000") "\u0000FS2\u00001")]
   (if (if rollback (and (= result -1) (not submitted) (= (vector-at clock 0) 101000))
           (and (= result 20) submitted (= (vector-at clock 0) 100000))) 0 1)))'''
compiler=Path('/tmp/codex-amu-1762/seed/bootstrap/aarch64-macos/amu-full-root.macho')
pack=Path('/private/tmp/awai154-fs2-darwin-gate/candidate.macho')
for p,digest in [(compiler,'920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6'),(pack,'f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d')]:
 if hashlib.sha256(p.read_bytes()).hexdigest()!=digest: raise RuntimeError('pin mismatch')
def limit(): resource.setrlimit(resource.RLIMIT_CPU,(3,3))
with tempfile.TemporaryDirectory(prefix='awai-count-submit-',dir='/private/tmp') as d:
 root=Path(d); fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY)
 if fd!=3: os.dup2(fd,3)
 def run(args,expected=0):
  p=subprocess.run([str(x) for x in args],cwd=root,pass_fds=(3,),env={'PATH':'/nonexistent'},capture_output=True,timeout=12,preexec_fn=limit)
  if p.returncode!=expected or p.stderr: raise RuntimeError((args,p.returncode,p.stdout[-1000:],p.stderr[-1000:]))
  return p.stdout
 def build(name,source):
  (root/(name+'.kotoba')).write_text(source)
  run([compiler,'compile',name+'.kotoba','--target','aarch64-macos','--output',name+'.kseed'])
  out=run([compiler,'extract-native',name+'.kseed','--symbol','main','--output',name+'.raw'])
  m=re.search(rb':offset (\d+), :length (\d+), :arity 0}',out)
  if not m or int(m[2])!=(root/(name+'.raw')).stat().st_size: raise RuntimeError('extract')
  run([pack,name+'-unsigned.macho',name+'.raw','fs2-cli-stdio-general',m[1].decode()])
  p=root/(name+'.macho'); shutil.copyfile(root/(name+'-unsigned.macho'),p); p.chmod(0o700)
  subprocess.run(['/usr/bin/codesign','--force','--sign','-',str(p)],check=True,capture_output=True)
  return p
 source=pre+form('pr-window-seconds')+'\n'+form('pr-count')+'\n'+entry
 img=build('current',source)
 for mode in ('normal','rollback'):
  (root/'submitted').unlink(missing_ok=True); run([img,mode]); print('PASS submission',mode,flush=True)
 target='(pr-window-seconds floor deadline now seconds)'
 assert source.count(target)==1
 mutant=build('mutant',source.replace(target,'(pr-window-seconds started deadline now seconds)'))
 (root/'submitted').unlink(missing_ok=True); run([mutant,'rollback'],1)
 print('PASS submission rollback mutant detected',flush=True)
 print('SOURCE_SHA',hashlib.sha256(source.encode()).hexdigest(),flush=True)
 os.close(3)
 if fd!=3: os.close(fd)
