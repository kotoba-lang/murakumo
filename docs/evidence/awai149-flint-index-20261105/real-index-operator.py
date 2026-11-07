import hashlib,importlib.util,os,re,shutil,subprocess,tempfile,resource
from pathlib import Path
import sys
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('h','/Users/junkawasaki/github/workspaces/codex/awai147-job-archive-cursor/scripts/native/check-cron-decisions.py');h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
repo=Path('/Users/junkawasaki/github/workspaces/codex/awai149-oci-seal')
code=(repo/'docs/evidence/awai149-flint-stage-inventory-20261105/fixture.kotoba').read_text()
for file,names in {'fleet_model_flint_oci_stage.kotoba':['fk-index-valid?','fk-stage-index?','model-flint-oci-stage-main'],'fleet_flint_verified_inventory.kotoba':['model-flint-verified-inventory?'],'fleet_model_flint_oci_stage_test.kotoba':['test-flint-index']}.items():
 source=(repo/'kotoba'/file).read_text()
 for name in names:
  a,b=h.form(source,name);replacement=source[a:b]
  try:c,d=h.form(code,name);code=code[:c]+replacement+code[d:]
  except ValueError:code+='\n'+replacement+'\n'
a,b=h.form(code,'main');code=code[:a]+'(defn main [] :i64 (if (fk-index-valid? (ma-read \"/index.json\" 1048576)) 0 1))'+code[b:]
compiler=Path('/tmp/codex-amu-1762/seed/bootstrap/aarch64-macos/amu-full-root.macho');packager=Path('/private/tmp/awai-oci-range/candidate.macho')
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
for p,w in [(compiler,'920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6'),(packager,'df0e94211a35d84901ebcf2a8620cb653140d957732474ff77bd0cdd5b5bf4c5')]:
 if sha(p)!=w:raise ValueError('pin mismatch')
root=Path(tempfile.mkdtemp(prefix='awai150-meta-',dir='/private/tmp'));fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY)
if fd!=3:os.dup2(fd,3)
def run(cmd,expected=0):
 r=subprocess.run([str(x) for x in cmd],cwd=root,pass_fds=(3,),capture_output=True,timeout=12,env={'PATH':'/nonexistent'},preexec_fn=lambda:resource.setrlimit(resource.RLIMIT_CPU,(3,3)))
 if r.returncode!=expected or r.stderr:raise RuntimeError((r.returncode,r.stdout[-300:],r.stderr[-300:]))
 return r.stdout
def build(name,content):
 (root/(name+'.kotoba')).write_text(content);run([compiler,'compile',name+'.kotoba','--target','aarch64-macos','--output',name+'.kseed']);out=run([compiler,'extract-native',name+'.kseed','--symbol','main','--output',name+'.raw']);m=re.search(rb':offset (\d+), :length (\d+), :arity 0}',out)
 if not m or int(m[2])!=(root/(name+'.raw')).stat().st_size:raise ValueError('extraction mismatch')
 run([packager,name+'-unsigned.macho',name+'.raw','fs2-cli-stdio-general',m[1].decode()]);img=root/(name+'.macho');shutil.copyfile(root/(name+'-unsigned.macho'),img);img.chmod(0o700)
 subprocess.run(['/usr/bin/codesign','--force','--sign','-',str(img)],capture_output=True,check=True);subprocess.run(['/usr/bin/codesign','--verify','--strict',str(img)],capture_output=True,check=True)
 return img
actual=Path('/private/tmp/awai149-flint-real-index.json')
try:
 shutil.copyfile(actual,root/'index.json');(root/'index.json').chmod(0o600)
 positive=build('real-index',code)
 import time
 t=time.monotonic();run([positive]);print('PASS real index native',round(time.monotonic()-t,3),'sec',hashlib.sha256(code.encode()).hexdigest(),sha(positive))
finally:
 (root/'index.json').unlink(missing_ok=True);actual.unlink(missing_ok=True)
