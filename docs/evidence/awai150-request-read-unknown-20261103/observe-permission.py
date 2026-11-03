import os,pathlib,resource,subprocess
p=pathlib.Path('/private/tmp/awai150-request-read-unknown');d=p/'deny-case';d.mkdir();(d/'request').write_text('wanted');os.chmod(d,0);fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
try:
 try:os.stat(d/'request');raise AssertionError('access unexpectedly succeeded')
 except PermissionError:print('parent-stat permission denied',flush=True)
 for label,path in [('missing','/missing/request'),('permission-denied','/deny-case/request')]:
  r=subprocess.run([str(p/'fs-status.macho'),path],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5,preexec_fn=lambda:resource.setrlimit(resource.RLIMIT_CPU,(3,3)));print(label,r.returncode,r.stdout.strip(),flush=True);assert r.returncode==0 and r.stdout.strip()=='reply=0'
finally:os.close(fd);os.chmod(d,0o700)
