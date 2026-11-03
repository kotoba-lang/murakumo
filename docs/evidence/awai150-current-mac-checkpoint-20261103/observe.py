import os,pathlib,subprocess,json,hashlib,platform,stat,resource
p=pathlib.Path('/private/tmp/awai150-current-mac-checkpoint');f=p/'fixture4';exe=p/'fixture.macho';env={'PATH':'/usr/bin:/bin','HOME':str(f/'home'),'GIT_CONFIG_NOSYSTEM':'1','LANG':'C'};fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
rows=[]
def limit(): resource.setrlimit(resource.RLIMIT_CPU,(5,5))
def guest(name,args):
 r=subprocess.run([str(exe),*args],cwd=p,pass_fds=(fd,),capture_output=True,timeout=30,preexec_fn=limit);row={'case':name,'exit':r.returncode,'stdout':r.stdout.decode(errors='replace'),'stderrBytes':len(r.stderr)};rows.append(row);assert r.returncode==(1 if name in ('reject-wrong-policy','refuse-existing-destination','reject-corrupted-block','reject-unknown-command','reject-missing-recovery') else 0),row;return r.stdout.decode()
def git(repo,*args):
 r=subprocess.run(['/usr/bin/git','-C',str(f/repo),*args],env=env,capture_output=True,timeout=5,check=True);return r.stdout
def snapshot(repo):
 return {'head':git(repo,'rev-parse','HEAD').decode().strip(),'status':git(repo,'status','--porcelain=v1','--untracked-files=all').decode(),'index':git(repo,'show',':tracked.txt').decode(),'worktree':(f/repo/'tracked.txt').read_text(),'untracked':(f/repo/'notes.txt').read_text(),'untrackedMode':oct(stat.S_IMODE((f/repo/'notes.txt').stat().st_mode))}
try:
 before=snapshot('repo');args=['capture','/fixture4/repo','/fixture4/store','/fixture4/input/seed','/fixture4/input/profile','/fixture4/input/policy','/fixture4/input/environment','/fixture4/input/summary','/fixture4/input/untracked','1','root','fixture'];out=guest('capture-real-git',args);line,encoded=out.split('\n',1);assert line.startswith('checkpoint/created '),out;cid=line.split(' ')[1];d=json.loads(encoded);v=d['payload'];assert json.loads((f/'store'/cid).read_text())==d and snapshot('repo')==before
 base=['/fixture4/store',cid,v['did'],v['profile'],v['policy'],v['environment'],'1','root','fixture']
 assert guest('verify-contract',['verify',*base])=='checkpoint/verified\n'
 wrong=base.copy();wrong[4]=v['environment'];out=guest('reject-wrong-policy',['verify',*wrong]);assert out!='checkpoint/verified\n' and out.startswith('checkpoint/'),out
 out=guest('restore-real-git',['restore',*base,'/fixture4/restored']);assert out=='checkpoint/restored\n',out
 after=snapshot('restored');assert before==after,(before,after)
 out=guest('refuse-existing-destination',['restore',*base,'/fixture4/restored']);assert out=='checkpoint/destination-exists\n' and snapshot('restored')==after,out
 block=f/'store'/v['untracked'][0]['cid'];original=block.read_bytes();block.write_bytes(b'CORRUPTED')
 try:
  out=guest('reject-corrupted-block',['verify',*base]);assert out=='checkpoint/missing-or-corrupt-block\n',out
 finally:block.write_bytes(original)
 assert guest('verify-after-restoring-fixture',['verify',*base])=='checkpoint/verified\n'
 assert guest('reject-unknown-command',['unknown'])=='checkpoint/command\n'
 assert guest('reject-missing-recovery',['verify-recovery','/fixture4/store','missing'])=='recovery/missing-or-corrupt\n'
 closure=guest('closure-confirmed',['closure',*base]);assert closure.endswith('checkpoint/closure-complete\n')
 assert snapshot('repo')==before and snapshot('restored')==after
finally:os.close(fd)
def sha(x):return hashlib.sha256(pathlib.Path(x).read_bytes()).hexdigest()
e={'schema':'murakumo.darwin-native-checkpoint-fixture.v1','host':{'os':platform.system(),'arch':platform.machine(),'macOS':platform.mac_ver()[0]},'checkpointSourceSha256':sha('/Users/junkawasaki/github/workspaces/codex/awai150-inference-replay-guard/kotoba/git_checkpoint.kotoba'),'fixtureSourceSha256':sha(p/'fixture.kotoba'),'fixtureRawSha256':sha(p/'fixture.raw'),'fixtureUnsignedSha256':sha(p/'fixture-unsigned.macho'),'fixtureSignedSha256':sha(exe),'runtimePackagerSourceSha256':sha('/Users/junkawasaki/github/workspaces/codex/awai150-amu-fullmode/seed/macos-fs2-runtime-packager.kotoba'),'compilerSha256':sha('/private/tmp/awai150-current-mac-checkpoint/root.elf'),'bootstrapPackagerSha256':sha('/private/tmp/awai154-fs2-exists-error/candidate.macho'),'gitVersion':'git version 2.50.1 (Apple Git-155)','checkpointCid':cid,'testPublicSeed':'00 repeated 32 bytes, synthetic only','sourceSnapshot':before,'restoredSnapshot':after,'cases':rows,'limits':'Actual checkpoint capture/verify/restore source except cp-spawn replaced by a privileged test-only adapter mapping virtual fixture paths to physical paths. One Mac and local Git only. No production scoped spawn, process-tree governor, actual GitHub archive, independent node, bot, MTP inference, malicious concurrent mutations, power-loss durability or release qualification. Git stderr is expected for untracked checks and fetch; results are verified via output and independent host Git inspection.'}
print(json.dumps(e,indent=2))
