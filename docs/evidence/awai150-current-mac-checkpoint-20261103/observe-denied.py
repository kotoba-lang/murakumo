import json,os,pathlib,resource,subprocess
p=pathlib.Path('/private/tmp/awai150-current-mac-checkpoint');f=p/'fixture4';v=json.loads((p/'results.json').read_text());capture=next(x for x in v['cases'] if x['case']=='capture-real-git')['stdout'];line,doc=capture.split('\n',1);cid=line.split(' ')[1];payload=json.loads(doc)['payload'];d=f/'denied';d.mkdir();os.chmod(d,0);fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
def limit():resource.setrlimit(resource.RLIMIT_CPU,(5,5))
try:
 try:os.stat(d/'restore');raise AssertionError('expected EACCES')
 except PermissionError:pass
 args=['restore','/fixture4/store',cid,payload['did'],payload['profile'],payload['policy'],payload['environment'],'1','root','fixture','/fixture4/denied/restore']
 r=subprocess.run([str(p/'fixture.macho'),*args],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=20,preexec_fn=limit);print('restore-denied',r.returncode,repr(r.stdout),flush=True);assert (r.returncode,r.stdout)==(1,'checkpoint/destination-exists\n')
finally:os.close(fd);os.chmod(d,0o700)
assert list(d.iterdir())==[]
