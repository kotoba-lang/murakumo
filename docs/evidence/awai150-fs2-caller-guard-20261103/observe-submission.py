import os,pathlib,resource,subprocess
p=pathlib.Path('/private/tmp/awai150-fs2-caller-guard');d=p/'denied';os.chmod(d,0);fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
def limits():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
def run(image,root):
 r=subprocess.run([str(p/(image+'.macho')),root],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5,preexec_fn=limits);assert r.returncode==0;return r.stdout.strip()
try:
 for image,root,expected in [('submission-current','/ok','profile/new-unit'),('submission-current','/denied','profile/submission-unknown'),('submission-previous','/denied','profile/new-unit')]:
  actual=run(image,root);print(image,root,actual,flush=True);assert actual==expected
finally:os.close(fd);os.chmod(d,0o700)
