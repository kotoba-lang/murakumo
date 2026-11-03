# Test-only: emulate another principal rewinding a private local bare ref.
import hashlib,json,os,pathlib,resource,subprocess
p=pathlib.Path('/private/tmp/awai150-head-graphql');f=p/'fixture5';fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
env={'PATH':'/usr/bin:/bin','HOME':str(f/'home'),'GIT_CONFIG_NOSYSTEM':'1','LANG':'C'}
def git(*args):
 r=subprocess.run(['/usr/bin/git','-C',str(f/'remote.git'),*args],env=env,capture_output=True,text=True,timeout=8);assert r.returncode==0,r.stderr;return r.stdout.strip()
def guest(args):return subprocess.run([str(p/'fixture.macho'),*args],cwd=p,env=env,pass_fds=(fd,),capture_output=True,text=True,timeout=40,preexec_fn=lambda:resource.setrlimit(resource.RLIMIT_CPU,(5,5)))
try:
 (f/'lost-reply').unlink();ref='refs/heads/itonami/head/'+hashlib.sha256(b'fixture').hexdigest();expected=git('rev-parse',ref);rewound=git('rev-parse',expected+'^');parent=json.loads(git('show',ref+':head.json'))['checkpoint'];(f/'input'/'summary').write_text('rewind test checkpoint')
 r=guest(['capture','/fixture5/repo','/fixture5/store','/fixture5/input/seed','/fixture5/input/profile','/fixture5/input/policy','/fixture5/input/environment','/fixture5/input/summary','/fixture5/input/untracked','4',parent,'fixture']);assert r.returncode==0,(r.stdout,r.stderr)
 line,raw=r.stdout.split('\n',1);cid=line.split()[1];v=json.loads(raw)['payload'];contract=[cid,v['did'],v['profile'],v['policy'],v['environment'],'4',parent,'fixture'];(f/'simulate-rewind').write_text('1')
 r=guest(['cas','/fixture5/work-rewind','/fixture5/store',*contract,'fixture/private','/usr/bin/gh',expected]);actual=git('rev-parse',ref)
 assert r.returncode!=0 and r.stdout.strip()=='head-cas/conflict-or-unknown' and actual==rewound,(r.returncode,r.stdout,r.stderr)
 print(json.dumps({'rewind_detected':True,'candidate_refused':True,'test_only_local_ref':True}),flush=True)
finally:os.close(fd)
