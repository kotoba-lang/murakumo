import os,pathlib,resource,subprocess
p=pathlib.Path('/private/tmp/awai150-fs2-caller-guard');(p/'ok').mkdir();d=p/'denied';d.mkdir();os.chmod(d,0);fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
def limits():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
def run(image,op,root):
 r=subprocess.run([str(p/(image+'.macho')),op,root],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5,preexec_fn=limits);assert r.returncode==0,(op,r.returncode,r.stderr);return r.stdout.rstrip('\n')
try:
 try:os.stat(d/'item');raise AssertionError('expected permission denial')
 except PermissionError:pass
 for op in ('put','copy','save','output','prepare','mark'):
  good='cid' if op=='put' else 'true';bad='' if op=='put' else 'false'
  a=run('current',op,'/ok');b=run('current',op,'/denied');c=run('previous',op,'/denied');print(op,repr(a),repr(b),repr(c),flush=True);assert (a,b,c)==(good,bad,good)
finally:os.close(fd);os.chmod(d,0o700)
