# Test-only local bare remote; never touches a network or a user repository.
import pathlib,subprocess
p=pathlib.Path('/private/tmp/awai150-head-graphql/fixture5')
env={'PATH':'/usr/bin:/bin','HOME':str(p/'home'),'GIT_CONFIG_NOSYSTEM':'1','LANG':'C'}
r=subprocess.run(['/usr/bin/git','init','--bare','--quiet','--template=',str(p/'remote.git')],env=env,capture_output=True,timeout=5)
assert r.returncode==0,r.stderr
