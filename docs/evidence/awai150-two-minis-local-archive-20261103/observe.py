# Test-only: local Git remote and stubbed repository privacy.
import os,pathlib,subprocess,json,resource,stat,hashlib
p=pathlib.Path('/private/tmp/awai150-mini-archive-4bac682e05');f=p/'fixture5';fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
hostenv={'PATH':'/usr/bin:/bin','HOME':str(f/'home'),'GIT_CONFIG_NOSYSTEM':'1','LANG':'C'}
def limit():resource.setrlimit(resource.RLIMIT_CPU,(5,5))
def guest(label,args,exit):
 r=subprocess.run([str(p/'fixture.macho'),*args],cwd=p,env=hostenv,pass_fds=(fd,),capture_output=True,text=True,timeout=25,preexec_fn=limit)
 print(label,r.returncode,r.stdout[:250].strip(),flush=True);assert r.returncode==exit,(label,r.returncode,r.stdout,r.stderr[-500:]);return r.stdout
def git(repo,*args):
 r=subprocess.run(['/usr/bin/git','-C',str(f/repo),*args],env=hostenv,capture_output=True,text=True,timeout=5);assert r.returncode==0,(args,r.stderr);return r.stdout
def snapshot(repo):
 return {'head':git(repo,'rev-parse','HEAD'),'status':git(repo,'status','--porcelain=v1','--untracked-files=all'),'index':git(repo,'show',':tracked.txt'),'working':(f/repo/'tracked.txt').read_text(),'untracked':(f/repo/'notes.txt').read_text(),'mode':oct(stat.S_IMODE((f/repo/'notes.txt').stat().st_mode))}
try:
 before=snapshot('repo');capture=guest('capture',['capture','/fixture5/repo','/fixture5/store','/fixture5/input/seed','/fixture5/input/profile','/fixture5/input/policy','/fixture5/input/environment','/fixture5/input/summary','/fixture5/input/untracked','1','root','fixture'],0);line,raw=capture.split('\n',1);assert line.startswith('checkpoint/created ');cid=line.split()[1];v=json.loads(raw)['payload'];contract=[cid,v['did'],v['profile'],v['policy'],v['environment'],'1','root','fixture']
 refused=guest('private-refusal',['archive','/fixture5/refused','/fixture5/store',*contract,'fixture/public','/usr/bin/gh'],1);assert refused=='archive/private-repository-unverified\n' and not (f/'refused').exists()
 stored=guest('archive',['archive','/fixture5/archive-work','/fixture5/store',*contract,'fixture/private','/usr/bin/gh'],0);parts=stored.split();assert len(parts)==4 and parts[0]=='archive/stored' and parts[1]==cid and parts[3] in ('acknowledged','unacknowledged')
 tree=git('remote.git','rev-parse','refs/heads/itonami/archive/'+cid+'^{tree}').strip();assert tree==parts[2]
 restored=guest('archive-restore',['archive-restore','/fixture5/restore-work','/fixture5/restored',*contract,'fixture/private','/usr/bin/gh'],0);assert restored.startswith('archive-restore/restored '+cid+' ')
 after=snapshot('restored');assert before==after and snapshot('repo')==before
 wrong=contract.copy();wrong[3]=v['environment'];refused=guest('wrong-policy',['archive-restore','/fixture5/wrong-work','/fixture5/wrong-dest',*wrong,'fixture/private','/usr/bin/gh'],1);assert refused=='archive-restore/readback-refused\n' and not (f/'wrong-dest').exists()
 print(json.dumps({'checkpoint':cid,'tree':tree,'snapshotEqual':before==after,'privateStub':True,'localFileRemote':True,'cases':['capture','privacy-refusal','archive','independent-remote-tree','archive-restore','wrong-policy']},sort_keys=True),flush=True)
finally:os.close(fd)
