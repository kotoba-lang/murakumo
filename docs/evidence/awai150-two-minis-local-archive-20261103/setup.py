import os,pathlib,subprocess
p=pathlib.Path('/private/tmp/awai150-mini-archive-4bac682e05');f=p/'fixture5';f.mkdir(mode=0o700,exist_ok=False)
for n in ('repo','store','logs','input','home'):(f/n).mkdir(mode=0o700)
env={'PATH':'/usr/bin:/bin','HOME':str(f/'home'),'GIT_CONFIG_NOSYSTEM':'1','LANG':'C'}
def git(*args):
 r=subprocess.run(['/usr/bin/git','-C',str(f/'repo'),*args],env=env,capture_output=True,timeout=5);assert r.returncode==0,(args,r.stderr);return r.stdout
try:
 git('init','--quiet','--template=');(f/'repo'/'tracked.txt').write_text('base\n');git('add','--','tracked.txt');git('-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','--quiet','-m','fixture');(f/'repo'/'tracked.txt').write_text('staged\n');git('add','--','tracked.txt');(f/'repo'/'tracked.txt').write_text('unstaged\n');(f/'repo'/'notes.txt').write_text('untracked content\n');os.chmod(f/'repo'/'notes.txt',0o644)
 for name,data in [('seed','00'*32+'\n'),('profile','test profile\n'),('policy','test policy\n'),('environment','test environment\n'),('summary','synthetic checkpoint\n'),('untracked','["notes.txt"]')]: (f/'input'/name).write_text(data)
 print('status',repr(git('status','--porcelain=v1','--untracked-files=all')))
except:raise
