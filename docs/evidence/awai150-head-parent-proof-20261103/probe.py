import os,pathlib,resource,subprocess
p=pathlib.Path('/private/tmp/awai150-parent-proof'); repo=p/'fixture5/repo'
env={'PATH':'/usr/bin:/bin','HOME':str(p/'fixture5/home'),'GIT_CONFIG_NOSYSTEM':'1','LANG':'C','GIT_AUTHOR_NAME':'Fixture','GIT_AUTHOR_EMAIL':'fixture@example.invalid','GIT_COMMITTER_NAME':'Fixture','GIT_COMMITTER_EMAIL':'fixture@example.invalid'}
def git(*args):
 r=subprocess.run(['/usr/bin/git','-C',str(repo),*args],env=env,input='fixture\n',capture_output=True,text=True,timeout=5);assert r.returncode==0,(args,r.stderr);return r.stdout.strip()
base=git('rev-parse','HEAD');tree=git('rev-parse','HEAD^{tree}')
other=git('commit-tree',tree);normal=git('commit-tree',tree,'-p',base);merge=git('commit-tree',tree,'-p',base,'-p',other)
git('update-ref','refs/heads/normal',normal);git('update-ref','refs/heads/merge',merge)
assert git('rev-parse','refs/heads/merge^')==base
fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
for label,ref,tip,expected,code in [('normal','refs/heads/normal',normal,base,0),('merge','refs/heads/merge',merge,base,1),('wrong-parent','refs/heads/normal',normal,other,1),('wrong-tip','refs/heads/normal',other,base,1),('root','HEAD',base,base,1)]:
 r=subprocess.run([str(p/'fixture.macho'),'parent-check','/fixture5/repo',ref,tip,expected],cwd=p,env=env,pass_fds=(fd,),capture_output=True,text=True,timeout=10,preexec_fn=lambda:resource.setrlimit(resource.RLIMIT_CPU,(5,5)))
 print(label,'exit',r.returncode,r.stdout.strip(),flush=True);assert r.returncode==code,(label,r.stdout,r.stderr)
os.close(fd)
