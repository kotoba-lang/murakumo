#!/usr/bin/env python3
"""Native selected-source jo-put empty-read refusal; stubs inject FS2 results."""
import hashlib,importlib.util,os,re,resource,shutil,subprocess,sys,tempfile
from pathlib import Path
sys.dont_write_bytecode=True
repo=Path('/Users/junkawasaki/github/workspaces/codex/awai150-verified-cron-input')
spec=importlib.util.spec_from_file_location('forms','/Users/junkawasaki/github/workspaces/codex/awai147-job-archive-cursor/scripts/native/check-cron-decisions.py');h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
src=(repo/'kotoba/fleet_job_output.kotoba').read_text();a,b=h.form(src,'jo-put');function=src[a:b]
pre=r'''(ns empty-output-fixture (:export [main]))
(defn- cp-cid [b :vector-i64] :string "cid")
(defn- cp-size [p :string] :i64 (if (= (typed-cap-call 38 :string :string "0") "nonempty") 1 0))
(defn- cp-range [p :string off :i64 n :i64] :vector-i64 (if (= n 1) [7] (vector-alloc 0)))
(defn- cp-write [p :string b :vector-i64] :bool false)
(defn- cp-empty-readable? [p :string] :bool (= (typed-cap-call 38 :string :string "0") "readable"))
(defn- cp-fs [op :string p :string tail :string] :string
 (if (= op "D") "1" (let [w (typed-cap-call 35 :bytes :bytes (string-to-utf8 "\u0000FS2\u0000B/synced\u0000x"))] "1")))
'''
main=r'''(defn main [] :i64
 (let [mode (typed-cap-call 38 :string :string "0") readable (or (= mode "readable") (= mode "nonempty"))
       result (jo-put "/store" (if (= mode "nonempty") [7] (vector-alloc 0)))
       synced (= (typed-cap-call 35 :string :string "\u0000FS2\u0000D/synced\u0000") "\u0000FS2\u00001")]
  (if (and (= result (if readable "cid" "")) (= synced readable)) 0 1)))'''
compiler=Path('/tmp/codex-amu-1762/seed/bootstrap/aarch64-macos/amu-full-root.macho');pack=Path('/private/tmp/awai154-fs2-darwin-gate/candidate.macho')
for p,pin in ((compiler,'920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6'),(pack,'f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d')):
 if hashlib.sha256(p.read_bytes()).hexdigest()!=pin:raise RuntimeError('pin mismatch')
with tempfile.TemporaryDirectory(prefix='awai150-empty-block-',dir='/private/tmp') as d:
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
 content=pre+function+'\n'+main;img=build('fixture',content)
 for mode in ('readable','unreadable','nonempty'):
  (root/'synced').unlink(missing_ok=True);run([img,mode]);print('PASS existing empty block',mode,flush=True)
 guard='(or (> size 0) (cp-empty-readable? path))'
 if content.count(guard)!=1:raise RuntimeError('guard not unique')
 mutant=build('mutant',content.replace(guard,'true'));(root/'synced').unlink(missing_ok=True);run([mutant,'unreadable'],1)
 print('PASS unreadable empty-block guard mutant detected',flush=True)
 print('SOURCE_SHA',hashlib.sha256(content.encode()).hexdigest(),flush=True)
 os.close(3)
 if fd!=3:os.close(fd)
