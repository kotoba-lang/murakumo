# Test-only: corrupt a block in a task-owned local bare Git archive.
import hashlib,json,os,pathlib,resource,subprocess
p=pathlib.Path('/private/tmp/awai150-head-restore');f=p/'fixture5';fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
env={'PATH':'/usr/bin:/bin','HOME':str(f/'home'),'GIT_CONFIG_NOSYSTEM':'1','LANG':'C'}
def git(repo,*args):
 r=subprocess.run(['/usr/bin/git','-C',str(repo),*args],env=env,capture_output=True,text=True,timeout=8);assert r.returncode==0,(args,r.stderr);return r.stdout.strip()
try:
 bare=f/'remote.git';href='refs/heads/itonami/head/'+hashlib.sha256(b'fixture').hexdigest();tip=git(bare,'rev-parse',href);cid=json.loads(git(bare,'show',href+':head.json'))['checkpoint'];aref='refs/heads/itonami/archive/'+cid;m=json.loads(git(bare,'show',aref+':manifest.json'))
 clone=f/'corrupt-clone';clone.mkdir();git(clone,'init','--quiet','--template=');git(clone,'fetch','--quiet','--no-tags',str(bare),aref);git(clone,'checkout','--quiet','--detach','FETCH_HEAD')
 block=clone/'blocks'/cid;block.write_bytes(block.read_bytes()+b'corruption');git(clone,'add','--','blocks/'+cid);git(clone,'-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','--quiet','-m','synthetic corruption');git(clone,'push','--quiet',str(bare),'HEAD:'+aref)
 args=['head-restore','/fixture5/read-corrupt','/fixture5/dest-corrupt',tip,'fixture',m['author'],m['profile'],m['policy'],m['environment'],'fixture/private','/usr/bin/gh']
 r=subprocess.run([str(p/'fixture.macho'),*args],cwd=p,env=env,pass_fds=(fd,),capture_output=True,text=True,timeout=50,preexec_fn=lambda:resource.setrlimit(resource.RLIMIT_CPU,(5,5)))
 print(json.dumps({'exit':r.returncode,'output':r.stdout.strip(),'dest_exists':(f/'dest-corrupt').exists()}));assert r.returncode!=0 and r.stdout.strip()=='head-restore/archive-or-materialize-refused' and not (f/'dest-corrupt').exists()
finally:os.close(fd)
