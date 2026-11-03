# Run after observe.py has created its synthetic directories.
import os,resource,pathlib,subprocess
p=pathlib.Path('/private/tmp/awai150-request-unknown');fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
def limit():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
try:
 for binary,name,expected in [('head','stable','bridge/head-ready'),('head','partial','bridge/request-unknown'),('head','error','bridge/request-unknown'),('head-old','partial','bridge/head-ready'),('head-old','error','bridge/head-ready')]:
  r=subprocess.run([str(p/(binary+'.macho')),'/'+name+'/request'],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5,preexec_fn=limit)
  print(binary,name,r.returncode,r.stdout.strip());assert r.returncode==0 and r.stdout.strip()==expected
finally:os.close(fd)
