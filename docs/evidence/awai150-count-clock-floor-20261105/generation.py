import hashlib,importlib.util,os,re,resource,shutil,subprocess,sys,tempfile
from pathlib import Path
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('f','/Users/junkawasaki/github/workspaces/codex/awai147-job-archive-cursor/scripts/native/check-cron-decisions.py'); h=importlib.util.module_from_spec(spec); spec.loader.exec_module(h)
s=Path('/private/tmp/awai-flint-clock-cross.kotoba').read_text()
def put(name,code):
 global s
 a,b=h.form(s,name);s=s[:a]+code+s[b:]
put('pr-count','(defn- pr-count [r :vector-i64 c :string n :i64 b :vector-i64 st :i64 d :i64 reuse :bool clock :vector-i64] :i64 (let [x (vector-assoc! clock 0 106000)] 20))')
put('pr-unit-state','(defn- pr-unit-state [u :string] :string "unit/not-found")')
put('pr-prepare-submit','(defn- pr-prepare-submit [r :string m :string b :vector-i64] :bool (cp-write "/prepared" [1]))')
put('pr-wall-ms','(defn- pr-wall-ms [] :i64 (if (= (cp-fs "D" "/prepared" "") "1") (if (= (cp-arg 0) "normal") 107000 106000) 106500))')
put('pr-submit','(defn- pr-submit [u :string r :string p :string n :i64 runtime :vector-i64] :bool (let [x (cp-write "/submitted" [1])] false))')
put('main',r'''(defn main [] :i64
 (let [instance "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
       old (cp-bytes "{\"next\":1,\"previous\":\"none\",\"task\":\"task\"}")
       runtime (cp-bytes (str "{\"authorizationFile\":\"/auth\",\"contextTokens\":8192,\"endpoint\":\"http://localhost/v1/chat/completions\",\"kind\":\"itonami.readonly-profile-runtime-local-flint/1\",\"maxTokens\":6144,\"model\":\"flint\",\"reasoningMemory\":\"disabled\",\"serverInstance\":\"" instance "\",\"socket\":\"/sock\",\"task\":\"task\"}"))
       setup (and (cp-mkdir "/repo") (cp-mkdir "/repo/profile") (cp-mkdir "/cache") (cp-write "/repo/profile/itonami.edn" (cp-bytes "profile")) (cp-write "/repo/profile/SOUL.md" (cp-bytes "role")) (cp-write "/repo/state.json" old))
       clock [100000] normal (= (cp-arg 0) "normal")
       result (if setup (pr-step-range "/repo" runtime "/cache" 1 10 2 64 100000 110000 2 clock) "setup-failed")
       submitted (= (cp-fs "D" "/submitted" "") "1")
       busy (= (cp-fs "D" (str "/cache/server-" instance ".busy") "") "1")]
  (if (and (= result "profile/inference-unavailable-state-retained") (= submitted normal) busy (= (vector-at clock 0) (if normal 107000 106500)) (= (cp-cid (pr-read-bounded "/repo/state.json" 16384)) (cp-cid old))) 0 1)))''')
compiler=Path('/tmp/codex-amu-1762/seed/bootstrap/aarch64-macos/amu-full-root.macho');pack=Path('/private/tmp/awai154-fs2-darwin-gate/candidate.macho')
for p,w in [(compiler,'920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6'),(pack,'f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d')]:
 if hashlib.sha256(p.read_bytes()).hexdigest()!=w: raise RuntimeError('pin')
with tempfile.TemporaryDirectory(prefix='awai-generation-floor-',dir='/private/tmp') as d:
 root=Path(d);fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY)
 if fd!=3:os.dup2(fd,3)
 def run(args,expected=0):
  p=subprocess.run([str(x) for x in args],cwd=root,pass_fds=(3,),env={'PATH':'/nonexistent'},capture_output=True,timeout=12,preexec_fn=lambda:resource.setrlimit(resource.RLIMIT_CPU,(3,3)))
  if p.returncode!=expected or p.stderr:raise RuntimeError((args,p.returncode,p.stdout[-500:],p.stderr[-500:]))
  return p.stdout
 def build(name,code):
  (root/(name+'.kotoba')).write_text(code);run([compiler,'compile',name+'.kotoba','--target','aarch64-macos','--output',name+'.kseed']);out=run([compiler,'extract-native',name+'.kseed','--symbol','main','--output',name+'.raw']);m=re.search(rb':offset (\d+), :length (\d+), :arity 0}',out)
  if not m or int(m[2])!=(root/(name+'.raw')).stat().st_size:raise RuntimeError('extract')
  run([pack,name+'-unsigned.macho',name+'.raw','fs2-cli-stdio-general',m[1].decode()]);img=root/(name+'.macho');shutil.copyfile(root/(name+'-unsigned.macho'),img);img.chmod(0o700);subprocess.run(['/usr/bin/codesign','--force','--sign','-',str(img)],check=True,capture_output=True);return img
 def clean():
  for p in ('repo','cache'):shutil.rmtree(root/p,ignore_errors=True)
  for p in ('prepared','submitted'):(root/p).unlink(missing_ok=True)
 img=build('current',s)
 for mode in ('normal','rollback'):
  clean();run([img,mode]);print('PASS generation submit',mode,flush=True)
 anchor='(pr-window-seconds (vector-at clock 0) deadline now seconds)'
 if s.count(anchor)!=1:raise RuntimeError('guard')
 mutant=build('mutant',s.replace(anchor,'(pr-window-seconds started deadline now seconds)'))
 clean();run([mutant,'rollback'],1);print('PASS generation submit rollback mutant detected',flush=True)
 print('SOURCE_SHA',hashlib.sha256(s.encode()).hexdigest(),flush=True)
 os.close(3)
 if fd!=3:os.close(fd)
