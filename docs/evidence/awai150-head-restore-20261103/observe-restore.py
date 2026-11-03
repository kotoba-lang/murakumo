# Test-only local bare Git head-driven restore, no credentials or network.
import hashlib,json,os,pathlib,resource,stat,subprocess
p=pathlib.Path('/private/tmp/awai150-head-restore');f=p/'fixture5';fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
env={'PATH':'/usr/bin:/bin','HOME':str(f/'home'),'GIT_CONFIG_NOSYSTEM':'1','LANG':'C'}
def limit():resource.setrlimit(resource.RLIMIT_CPU,(5,5))
def guest(args):return subprocess.run([str(p/'fixture.macho'),*args],cwd=p,env=env,pass_fds=(fd,),capture_output=True,text=True,timeout=50,preexec_fn=limit)
def git(repo,*args):
 r=subprocess.run(['/usr/bin/git','-C',str(repo),*args],env=env,capture_output=True,text=True,timeout=8);assert r.returncode==0,(args,r.stderr);return r.stdout.strip()
def snapshot(repo):
 return {'head':git(repo,'rev-parse','HEAD'),'status':git(repo,'status','--porcelain=v1','--untracked-files=all'),'index':git(repo,'show',':tracked.txt'),'working':(repo/'tracked.txt').read_text(),'untracked':(repo/'notes.txt').read_text(),'mode':oct(stat.S_IMODE((repo/'notes.txt').stat().st_mode))}
try:
 bare=f/'remote.git';ref='refs/heads/itonami/head/'+hashlib.sha256(b'fixture').hexdigest();expected=git(bare,'rev-parse',ref);head=json.loads(git(bare,'show',ref+':head.json'));cid=head['checkpoint'];aref='refs/heads/itonami/archive/'+cid;manifest=json.loads(git(bare,'show',aref+':manifest.json'))
 contract=['fixture',manifest['author'],manifest['profile'],manifest['policy'],manifest['environment'],'fixture/private','/usr/bin/gh']
 def restore(label,tip=expected,parts=contract,dest=None):
  out=dest or '/fixture5/dest-'+label;r=guest(['head-restore','/fixture5/read-'+label,out,tip,*parts]);print(label,r.returncode,r.stdout.strip(),flush=True);return r
 r=restore('good');assert r.returncode==0 and r.stdout.startswith('head-restore/restored '+cid+' '+expected+' '),(r.stdout,r.stderr);assert snapshot(f/'dest-good')==snapshot(f/'repo')
 r=restore('wrong-head','0'*40);assert r.returncode!=0 and r.stdout.strip()=='head-restore/head-refused' and not (f/'dest-wrong-head').exists()
 bad=contract.copy();bad[1]='did:key:wrong';r=restore('wrong-author',parts=bad);assert r.returncode!=0 and r.stdout.strip()=='head-restore/manifest-refused' and not (f/'dest-wrong-author').exists()
 (f/'existing').mkdir();r=restore('existing',dest='/fixture5/existing');assert r.returncode!=0 and r.stdout.strip()=='head-restore/input-refused' and not (f/'read-existing').exists()
 print(json.dumps({'restored_state_matches':True,'wrong_pin_refused':True,'wrong_author_refused':True,'existing_destination_refused':True}),flush=True)
finally:os.close(fd)
