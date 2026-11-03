import os,pathlib,resource,subprocess
p=pathlib.Path('/private/tmp/awai150-fs2-caller-guard');d=p/'denied';invalid=p/'invalid';invalid.mkdir(exist_ok=True);(invalid/'lease').write_text('unreadable');os.chmod(d,0);fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
def limits():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
def run(image,op):
 r=subprocess.run([str(p/(image+'.macho')),op],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5,preexec_fn=limits);assert r.returncode==0,(op,r.returncode,r.stderr);return r.stdout.strip()
try:
 for op,current,old in [('head','resident/head-unknown','resident/runtime-pins-refused'),('request','resident/request-unknown','resident/runtime-pins-refused'),('lease','empty','lease'),('invalid','empty','lease')]:
  a=run('resident-current',op);b=run('resident-previous',op);print(op,a,b,flush=True);assert (a,b)==(current,old)
 assert run('resident-current','okay')=='resident/runtime-pins-refused'
 assert run('resident-current','lease-ok')=='lease'
finally:os.close(fd);os.chmod(d,0o700)
