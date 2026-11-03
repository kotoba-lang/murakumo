# Test-only local Git CAS observation. Never contacts GitHub or real bot state.
import concurrent.futures,hashlib,json,os,pathlib,resource,subprocess
p=pathlib.Path('/private/tmp/awai150-head-graphql');f=p/'fixture5';fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
hostenv={'PATH':'/usr/bin:/bin','HOME':str(f/'home'),'GIT_CONFIG_NOSYSTEM':'1','LANG':'C'}
def limit():resource.setrlimit(resource.RLIMIT_CPU,(5,5))
def guest(args):return subprocess.run([str(p/'fixture.macho'),*args],cwd=p,env=hostenv,pass_fds=(fd,),capture_output=True,text=True,timeout=40,preexec_fn=limit)
def git(dir,*args):
 r=subprocess.run(['/usr/bin/git','-C',str(dir),*args],env=hostenv,capture_output=True,text=True,timeout=8);assert r.returncode==0,(args,r.stderr);return r.stdout.strip()
def capture(parent,epoch,summary):
 (f/'input'/'summary').write_text(summary)
 r=guest(['capture','/fixture5/repo','/fixture5/store','/fixture5/input/seed','/fixture5/input/profile','/fixture5/input/policy','/fixture5/input/environment','/fixture5/input/summary','/fixture5/input/untracked',str(epoch),parent,'fixture'])
 assert r.returncode==0,(r.stdout,r.stderr);line,raw=r.stdout.split('\n',1);cid=line.split()[1];v=json.loads(raw)['payload']
 return [cid,v['did'],v['profile'],v['policy'],v['environment'],str(epoch),parent,'fixture']
try:
 base=capture('root',1,'base synthetic');a=capture(base[0],2,'candidate A');b=capture(base[0],2,'candidate B');assert a[0]!=b[0]
 genesis=f/'genesis';genesis.mkdir();git(genesis,'init','--quiet','--template=')
 (genesis/'head.json').write_text(json.dumps({'checkpoint':base[0],'kind':'itonami.github-head/1','task':'fixture'},sort_keys=True,separators=(',',':')))
 git(genesis,'add','head.json');git(genesis,'-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','--quiet','-m','fixture genesis')
 old=git(genesis,'rev-parse','HEAD');ref='refs/heads/itonami/head/'+hashlib.sha256(b'fixture').hexdigest();git(genesis,'push','--quiet',str(f/'remote.git'),'HEAD:'+ref)
 def advance(pair):
  label,contract=pair;r=guest(['cas','/fixture5/work-'+label,'/fixture5/store',*contract,'fixture/private','/usr/bin/gh',old]);return label,contract,r
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:rows=list(pool.map(advance,[('a',a),('b',b)]))
 results=[(label,r.returncode,r.stdout.strip(),r.stderr[-200:]) for label,_,r in rows];print(json.dumps(results),flush=True)
 winners=[(label,c,r) for label,c,r in rows if r.returncode==0 and r.stdout.startswith('head-cas/advanced ')]
 assert len(winners)==1,results
 label,contract,w=winners[0];parts=w.stdout.split();tip=git(f/'remote.git','rev-parse',ref)
 assert len(parts)==4 and tip==parts[2] and git(f/'remote.git','rev-parse',tip+'^')==old
 body=git(f/'remote.git','show',tip+':head.json');assert json.loads(body)=={'checkpoint':contract[0],'kind':'itonami.github-head/1','task':'fixture'}
 assert all(r.returncode!=0 for l,_,r in rows if l!=label)
 print(json.dumps({'successes':len(winners),'remote_matches':True,'parent_matches':True,'winner':label}),flush=True)
finally:os.close(fd)
