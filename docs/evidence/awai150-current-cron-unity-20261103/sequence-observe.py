# Disposable VM only. All filesystem effects are in a task-owned path.
import os,pathlib,resource,subprocess,shutil
p=pathlib.Path('/private/tmp/awai150-cron-current-unity');root=pathlib.Path('/private/tmp/awai150-cron-sequence');e=dict(os.environ,KEXE_ALLOW='35,37,38',KEXE_CAP_RESOURCES_35='/private/tmp')
def lim():resource.setrlimit(resource.RLIMIT_CPU,(10,10))
r=subprocess.run([str(p/'root.elf'),'compile','sequence.kotoba','--target','aarch64-linux','--output','sequence2.elf','--format','static'],cwd=p,env=e,capture_output=True,text=True,timeout=30,preexec_fn=lim);print('compile',r.returncode,r.stdout.strip(),r.stderr.strip(),flush=True);assert r.returncode==0
(p/'sequence2.elf').chmod(0o700)
def setup():
 if root.exists():shutil.rmtree(root)
 root.mkdir()
 for x in ('definition','cursor','source'):(root/x).write_text('original')
 (root/'called').write_text('')
def run(mode):
 r=subprocess.run([str(p/'sequence2.elf'),mode],cwd=p,env=e,capture_output=True,text=True,timeout=10,preexec_fn=lim);assert r.returncode==0,(mode,r.returncode,r.stdout,r.stderr);return r.stdout.strip()
try:
 setup();a=run('success');assert a=='cron-resident/completed 60000' and (root/'cursor').read_text()=='advanced' and (root/'called').read_text()=='x';print('success',a,flush=True)
 for mode,expect in [('refresh-fail','cron-resident/retention-pending'),('archive-before-fail','cron-resident/archive-pending'),('idle','cron-resident/idle')]:
  setup();a=run(mode);assert a==expect and (root/'called').read_text()=='' and (root/'cursor').read_text()=='original';print(mode,a,flush=True)
 setup();a=run('quorum-fail');b=run('quorum-fail');assert a=='cron-resident/completion-unknown' and b=='cron-resident/dispatch-unknown' and (root/'called').read_text()=='x' and (root/'cursor').read_text()=='original';print('uncertain retry',a,b,flush=True)
 setup();a=run('archive-after-fail');assert a=='cron-resident/archive-pending' and (root/'called').read_text()=='x' and (root/'cursor').read_text()=='original';b=run('success');assert b=='cron-resident/completed 60000' and (root/'called').read_text()=='x' and (root/'cursor').read_text()=='advanced';print('archive recovery',a,b,flush=True)
finally:
 if root.exists():shutil.rmtree(root)
