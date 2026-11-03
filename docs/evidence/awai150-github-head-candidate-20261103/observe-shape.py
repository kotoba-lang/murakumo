# Test-only: reject a head ref with an extra file or a symlink.
import hashlib,json,os,pathlib,resource,subprocess
p=pathlib.Path('/private/tmp/awai150-head-cas');f=p/'fixture5';fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
env={'PATH':'/usr/bin:/bin','HOME':str(f/'home'),'GIT_CONFIG_NOSYSTEM':'1','LANG':'C'}
def limit():resource.setrlimit(resource.RLIMIT_CPU,(5,5))
def run(args):return subprocess.run([str(p/'fixture.macho'),*args],cwd=p,env=env,pass_fds=(fd,),capture_output=True,text=True,timeout=40,preexec_fn=limit)
def git(dir,*args):
 r=subprocess.run(['/usr/bin/git','-C',str(dir),*args],env=env,capture_output=True,text=True,timeout=8);assert r.returncode==0,(args,r.stderr);return r.stdout.strip()
def capture(task,parent,epoch):
 (f/'input'/'summary').write_text(task+' synthetic')
 r=run(['capture','/fixture5/repo','/fixture5/store','/fixture5/input/seed','/fixture5/input/profile','/fixture5/input/policy','/fixture5/input/environment','/fixture5/input/summary','/fixture5/input/untracked',str(epoch),parent,task]);assert r.returncode==0,(r.stdout,r.stderr)
 line,raw=r.stdout.split('\n',1);cid=line.split()[1];v=json.loads(raw)['payload'];return [cid,v['did'],v['profile'],v['policy'],v['environment'],str(epoch),parent,task]
try:
 for task,mode in [('extra','extra-file'),('link','symlink')]:
  parent=capture(task,'root',1);child=capture(task,parent[0],2);d=f/('genesis-'+task);d.mkdir();git(d,'init','--quiet','--template=')
  if mode=='symlink':(d/'head.json').symlink_to('target')
  else:
   (d/'head.json').write_text(json.dumps({'checkpoint':parent[0],'kind':'itonami.github-head/1','task':task},sort_keys=True,separators=(',',':')));(d/'extra.txt').write_text('extra')
  git(d,'add','--all');git(d,'-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','--quiet','-m','fixture genesis');old=git(d,'rev-parse','HEAD');ref='refs/heads/itonami/head/'+hashlib.sha256(task.encode()).hexdigest();git(d,'push','--quiet',str(f/'remote.git'),'HEAD:'+ref)
  r=run(['advance','/fixture5/work-'+task,'/fixture5/store',*child,'fixture/private','/usr/bin/gh',old]);assert r.returncode!=0 and r.stdout.strip()=='head/precondition-or-staging-refused',(mode,r.returncode,r.stdout,r.stderr)
  assert git(f/'remote.git','rev-parse',ref)==old
  print(json.dumps({'case':mode,'refused':True,'remote_unchanged':True}),flush=True)
finally:os.close(fd)
