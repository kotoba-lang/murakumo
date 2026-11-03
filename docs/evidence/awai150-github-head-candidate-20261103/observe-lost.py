# Test-only: mutate the returned status after a real local push.
import hashlib,json,os,pathlib,resource,subprocess
p=pathlib.Path('/private/tmp/awai150-head-cas');f=p/'fixture5';fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
env={'PATH':'/usr/bin:/bin','HOME':str(f/'home'),'GIT_CONFIG_NOSYSTEM':'1','LANG':'C'}
def limit():resource.setrlimit(resource.RLIMIT_CPU,(5,5))
def git(*args):
 r=subprocess.run(['/usr/bin/git','-C',str(f/'remote.git'),*args],env=env,capture_output=True,text=True,timeout=8);assert r.returncode==0,r.stderr;return r.stdout.strip()
def guest(binary,args):return subprocess.run([str(p/(binary+'.macho')),*args],cwd=p,env=env,pass_fds=(fd,),capture_output=True,text=True,timeout=40,preexec_fn=limit)
try:
 ref='refs/heads/itonami/head/'+hashlib.sha256(b'fixture').hexdigest();old=git('rev-parse',ref);parent=json.loads(git('show',old+':head.json'))['checkpoint'];(f/'input'/'summary').write_text('third synthetic checkpoint')
 r=guest('fixture',['capture','/fixture5/repo','/fixture5/store','/fixture5/input/seed','/fixture5/input/profile','/fixture5/input/policy','/fixture5/input/environment','/fixture5/input/summary','/fixture5/input/untracked','3',parent,'fixture']);assert r.returncode==0,(r.stdout,r.stderr)
 line,raw=r.stdout.split('\n',1);cid=line.split()[1];v=json.loads(raw)['payload'];contract=[cid,v['did'],v['profile'],v['policy'],v['environment'],'3',parent,'fixture']
 r=guest('lost',['advance','/fixture5/work-lost','/fixture5/store',*contract,'fixture/private','/usr/bin/gh',old]);print(r.returncode,r.stdout.strip(),flush=True)
 parts=r.stdout.split();assert r.returncode==0 and len(parts)==4 and parts[0]=='head/advanced' and parts[1]==cid and parts[3]=='unacknowledged'
 tip=git('rev-parse',ref);assert tip==parts[2] and git('rev-parse',tip+'^')==old and json.loads(git('show',tip+':head.json'))['checkpoint']==cid
 print(json.dumps({'returned':'unacknowledged','independent_tip_matches':True,'parent_matches':True}),flush=True)
finally:os.close(fd)
