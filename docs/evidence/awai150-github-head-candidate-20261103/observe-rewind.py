# Test-only: demonstrate why a normal push is not exact expected-OID CAS.
import json,os,pathlib,subprocess
p=pathlib.Path('/private/tmp/awai150-head-cas/rewind');p.mkdir();(p/'work').mkdir();(p/'home').mkdir()
env={'PATH':'/usr/bin:/bin','HOME':str(p/'home'),'GIT_CONFIG_NOSYSTEM':'1','LANG':'C'}
def git(path,*args):
 r=subprocess.run(['/usr/bin/git','-C',str(path),*args],env=env,capture_output=True,text=True,timeout=8);assert r.returncode==0,(args,r.stderr);return r.stdout.strip()
subprocess.run(['/usr/bin/git','init','--bare','--quiet','--template=',str(p/'remote.git')],env=env,check=True,timeout=8)
w=p/'work';bare=p/'remote.git';git(w,'init','--quiet','--template=')
def commit(content):
 (w/'file').write_text(content);git(w,'add','file');git(w,'-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','--quiet','-m',content);return git(w,'rev-parse','HEAD')
a=commit('A');b=commit('B');ref='refs/heads/test';git(w,'push','--quiet',str(bare),'HEAD:'+ref);c=commit('C')
# The test owns this bare remote; this directly simulates a privileged rewind.
git(bare,'update-ref',ref,a,b);git(w,'push','--quiet',str(bare),'HEAD:'+ref)
assert git(bare,'rev-parse',ref)==c and git(bare,'rev-parse',c+'^')==b
print(json.dumps({'remote_was_rewound_from_expected':True,'normal_push_succeeded':True,'test_only':True}))
