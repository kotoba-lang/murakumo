import hashlib,importlib.util,os,re,resource,shutil,subprocess,sys,tempfile
from pathlib import Path
sys.dont_write_bytecode=True
repo=Path('/Users/junkawasaki/github/workspaces/codex/awai-flint-response-contract')
spec=importlib.util.spec_from_file_location('f','/Users/junkawasaki/github/workspaces/codex/awai147-job-archive-cursor/scripts/native/check-cron-decisions.py');h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
def form(file,name):
 s=(repo/'kotoba'/file).read_text();a,b=h.form(s,name);return s[a:b]
pre=r'''(ns cross-clock (:export [main]))
(defn- mark [p :string] :bool (let [x (typed-cap-call 35 :bytes :bytes (string-to-utf8 (str "\u0000FS2\u0000B/" p "\u0000x")))] true))
(defn- exists [p :string] :bool (= (typed-cap-call 35 :string :string (str "\u0000FS2\u0000D/" p "\u0000")) "\u0000FS2\u00001"))
(defn- rt-lease [a :vector-i64 e :i64] :vector-i64 [1])
(defn- cp-num [a :vector-i64 i :i64 k :string] :i64 200000)
(defn- cp-str [a :vector-i64 i :i64 k :string] :string "/cache")
(defn- rt-now [] :i64 (if (exists "seen") (if (= (typed-cap-call 38 :string :string "0") "rollback") 100500 102000) (let [z (mark "seen")] 100000)))
(defn- rt-post-problem [a :vector-i64 b :vector-i64 c :vector-i64 d :vector-i64 n :i64] :string "resident/admission-held")
(defn- rf-budget [a :i64 b :i64 c :i64] :i64 10)
(defn- rt-clock-window? [a :i64 b :i64] :bool true)
(defn- rt-runtime [a :vector-i64] :vector-i64 [1])
(defn- pr-local-flint? [a :vector-i64] :bool true)
(defn- pr-step-resident [repo :string runtime :vector-i64 cache :string next :i64 seconds :i64 started :i64 deadline :i64 phase :i64 clock :vector-i64] :string
 (if (= phase 1) (let [z (vector-assoc! clock 0 101000)] "profile/flint-count-ready") (let [z (mark "generated")] "generated")))
'''
main=r'''(defn main [] :i64
 (let [mode (typed-cap-call 38 :string :string "0") result (rt-dispatch [1] [1] [1] "/repo" 1)]
  (if (if (= mode "rollback") (and (= result "profile/budget-refused") (not (exists "generated")))
       (and (= result "generated") (exists "generated"))) 0 1)))'''
base=pre+'\n'+form('profile_recovery.kotoba','pr-window-seconds')+'\n'+form('profile_resident.kotoba','rt-dispatch')+'\n'+main
compiler=Path('/tmp/codex-amu-1762/seed/bootstrap/aarch64-macos/amu-full-root.macho');pack=Path('/private/tmp/awai154-fs2-darwin-gate/candidate.macho')
for p,d in [(compiler,'920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6'),(pack,'f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d')]:
 if hashlib.sha256(p.read_bytes()).hexdigest()!=d:raise RuntimeError('pin mismatch')
def limit():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
with tempfile.TemporaryDirectory(prefix='awai-flint-resident-clock-',dir='/private/tmp') as d:
 root=Path(d);fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY)
 if fd!=3:os.dup2(fd,3)
 def run(args,expected=0):
  r=subprocess.run([str(x) for x in args],cwd=root,pass_fds=(3,),env={'PATH':'/nonexistent'},capture_output=True,timeout=12,preexec_fn=limit)
  if r.returncode!=expected or r.stderr:raise RuntimeError((args,r.returncode,r.stdout[-300:],r.stderr[-300:]))
  return r.stdout
 def build(name,text):
  (root/(name+'.kotoba')).write_text(text);run([compiler,'compile',name+'.kotoba','--target','aarch64-macos','--output',name+'.kseed']);out=run([compiler,'extract-native',name+'.kseed','--symbol','main','--output',name+'.raw']);m=re.search(rb':offset (\d+), :length (\d+), :arity 0}',out)
  if not m or int(m[2])!=(root/(name+'.raw')).stat().st_size:raise RuntimeError('extract')
  run([pack,name+'-unsigned.macho',name+'.raw','fs2-cli-stdio-general',m[1].decode()]);p=root/(name+'.macho');shutil.copyfile(root/(name+'-unsigned.macho'),p);p.chmod(0o700);subprocess.run(['/usr/bin/codesign','--force','--sign','-',str(p)],check=True,capture_output=True);return p
 img=build('good',base)
 for mode in ('normal','rollback'):
  for x in ('seen','generated'):(root/x).unlink(missing_ok=True)
  run([img,mode]);print('PASS',mode,flush=True)
 anchor='(pr-window-seconds (vector-at clock 0) deadline before budget)'
 if base.count(anchor)!=1:raise RuntimeError('anchor changed')
 mutant=build('mutant',base.replace(anchor,'(pr-window-seconds now deadline before budget)'))
 for x in ('seen','generated'):(root/x).unlink(missing_ok=True)
 run([mutant,'rollback'],1);print('PASS resident floor mutant detected',flush=True)
 print('SOURCE_SHA',hashlib.sha256(base.encode()).hexdigest(),flush=True)
 os.close(3)
 if fd!=3:os.close(fd)
