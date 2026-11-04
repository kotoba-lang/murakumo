import hashlib,importlib.util,os,re,resource,shutil,subprocess,sys,tempfile
from pathlib import Path
sys.dont_write_bytecode=True
repo=Path('/Users/junkawasaki/github/workspaces/codex/awai-flint-response-contract')
spec=importlib.util.spec_from_file_location('f','/Users/junkawasaki/github/workspaces/codex/awai147-job-archive-cursor/scripts/native/check-cron-decisions.py');h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
src=(repo/'kotoba/profile_recovery.kotoba').read_text()
def form(name):
 a,b=h.form(src,name);return src[a:b]
pre=r'''(ns post-query-fixture (:export [main]))
(defn- mark [p :string] :bool (let [x (typed-cap-call 35 :bytes :bytes (string-to-utf8 (str "\u0000FS2\u0000B/" p "\u0000x")))] true))
(defn- pr-wall-ms [] :i64 (if (= (typed-cap-call 35 :string :string "\u0000FS2\u0000D/back\u0000") "\u0000FS2\u00001") 99000 (if (= (typed-cap-call 35 :string :string "\u0000FS2\u0000D/mark\u0000") "\u0000FS2\u00001") 110000 100000)))
(defn- pr-unit-code [u :string] :i64 (cond (= u "slow") (let [z (mark "mark")] 1) (= u "back") (let [z (mark "back")] 1) :else 1))
(defn- pr-pause [] :bool false)
(defn- pr-count-value [b :vector-i64] :i64 20)
(defn- pr-read-bounded [p :string n :i64] :vector-i64 (cond (= p "slow-read") (let [z (mark "mark")] [1]) (= p "back-read") (let [z (mark "back")] [1]) :else [1]))
(defn- cp-exists? [p :string] :bool true)
(defn- pr-read-response [p :string] :vector-i64 (cond (= p "slow-read") (let [z (mark "mark")] [1]) (= p "back-read") (let [z (mark "back")] [1]) :else [1]))
(defn- pr-answer [b :vector-i64] :string "ok")
(defn- pr-local-response? [r :vector-i64 b :vector-i64] :bool true)
(defn- pr-local-flint? [r :vector-i64] :bool (= (vector-at r 0) 1))
'''
main=r'''(defn main [] :i64
 (let [mode (typed-cap-call 38 :string :string "0") slow (= mode "slow") reading (= mode "slow-read") back (= mode "back") backread (= mode "back-read")
       unit (cond slow "slow" back "back" :else "fast") path (cond reading "slow-read" backread "back-read" :else "ok")
       count (pr-count-wait unit path 100000 110000 10)
       expected (if (or slow reading back backread) -1 20)]
  (if (= count expected) 0 1)))'''
main2=r'''(defn main [] :i64
 (let [mode (typed-cap-call 38 :string :string "0") slow (= mode "slow") reading (= mode "slow-read") back (= mode "back") backread (= mode "back-read") nonflint (= mode "nonflint")
       unit (cond (or slow nonflint) "slow" back "back" :else "fast") path (cond reading "slow-read" backread "back-read" :else "ok")
       value (pr-wait unit path (if nonflint [0] [1]) 100000 110000 10)]
  (if (= value (not (or slow reading back backread))) 0 1)))'''
base=pre+'\n'+form('pr-window-seconds')+'\n'+form('pr-count-wait')+'\n'+form('pr-wait')+'\n'
compiler=Path('/tmp/codex-amu-1762/seed/bootstrap/aarch64-macos/amu-full-root.macho');pack=Path('/private/tmp/awai154-fs2-darwin-gate/candidate.macho')
for p,d in [(compiler,'920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6'),(pack,'f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d')]:
 if hashlib.sha256(p.read_bytes()).hexdigest()!=d:raise RuntimeError('pin')
def limit():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
with tempfile.TemporaryDirectory(prefix='awai-flint-query-deadline-',dir='/private/tmp') as d:
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
 for name,entry in [('count',main),('generation',main2)]:
  img=build(name,base+entry)
  for mode in (('fast','slow','slow-read','back','back-read','nonflint') if name=='generation' else ('fast','slow','slow-read','back','back-read')):
   (root/'mark').unlink(missing_ok=True);(root/'back').unlink(missing_ok=True);run([img,mode]);print('PASS',name,mode,flush=True)
  q='(< (pr-window-seconds now deadline after-query seconds) 1)'; assert base.count(q)==2
  muttext=(base+entry).replace(q,'false').replace('(> (pr-window-seconds after-query deadline after-read seconds) 0)','true').replace('(> (pr-window-seconds after-query deadline (pr-wall-ms) seconds) 0)','true')
  mutant=build(name+'-mutant',muttext)

  for mode in ('slow','slow-read','back','back-read'):
   (root/'mark').unlink(missing_ok=True);(root/'back').unlink(missing_ok=True);run([mutant,mode],1);print('PASS',name,mode,'deadline guard mutant refused',flush=True)
  print('SOURCE_SHA',name,hashlib.sha256((base+entry).encode()).hexdigest(),flush=True)
 os.close(3)
 if fd!=3:os.close(fd)
