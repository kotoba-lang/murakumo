import os,pathlib,subprocess,resource
p=pathlib.Path('/private/tmp/awai150-mac-archive-flow');f=p/'fixture5';env={'PATH':'/usr/bin:/bin','HOME':str(f/'home'),'GIT_CONFIG_NOSYSTEM':'1','LANG':'C'}
for name in ('config-safe3','config-unsafe3'):
 r=subprocess.run(['/usr/bin/git','init','--quiet','--template=',str(f/name)],env=env,capture_output=True,timeout=5);assert r.returncode==0
r=subprocess.run(['/usr/bin/git','-C',str(f/'config-unsafe3'),'config','--local','credential.helper','synthetic-helper'],env=env,capture_output=True,timeout=5);assert r.returncode==0
fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
try:
 for name,expect in [('config-safe3','safe'),('config-unsafe3','refused')]:
  r=subprocess.run([str(p/'config-fixture.macho'),'/fixture5/'+name],cwd=p,env=env,pass_fds=(fd,),capture_output=True,text=True,timeout=10,preexec_fn=lambda:resource.setrlimit(resource.RLIMIT_CPU,(5,5)));print(name,r.returncode,r.stdout.strip(),flush=True);assert (r.returncode,r.stdout.strip())==(0,expect)
finally:os.close(fd)
