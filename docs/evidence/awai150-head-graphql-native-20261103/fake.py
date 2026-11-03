# Test-only emulation; production uses pinned gh GraphQL transport.
import base64,json,os,pathlib,subprocess,sys
p=pathlib.Path('/private/tmp/awai150-head-graphql/fixture5');v=json.loads(pathlib.Path(sys.argv[1]).read_text());x=v['variables']['input'];assert 'expectedHeadOid' in x and x['branch']['repositoryNameWithOwner']=='fixture/private'
branch=x['branch']['branchName'];assert branch.startswith('itonami/head/') and len(branch)==len('itonami/head/')+64
f=x['fileChanges']['additions'];assert len(f)==1 and f[0]['path']=='head.json';body=base64.b64decode(f[0]['contents'],validate=True);json.loads(body)
env={'PATH':'/usr/bin:/bin','HOME':str(p/'home'),'GIT_CONFIG_NOSYSTEM':'1','GIT_AUTHOR_NAME':'Fixture','GIT_AUTHOR_EMAIL':'fixture@example.invalid','GIT_COMMITTER_NAME':'Fixture','GIT_COMMITTER_EMAIL':'fixture@example.invalid'}
def git(args,data=None):
 r=subprocess.run(['/usr/bin/git','-C',str(p/'remote.git'),*args],input=data,capture_output=True,env=env,timeout=8)
 if r.returncode:sys.exit(1)
 return r.stdout.decode().strip()
blob=git(['hash-object','-w','--stdin'],body);tree=git(['mktree'],('100644 blob '+blob+'\thead.json\n').encode());oid=git(['commit-tree',tree,'-p',x['expectedHeadOid']],b'Synthetic CAS commit\n')
if (p/'simulate-rewind').exists():
 previous=git(['rev-parse',x['expectedHeadOid']+'^']);git(['update-ref','refs/heads/'+branch,previous,x['expectedHeadOid']])
git(['update-ref','refs/heads/'+branch,oid,x['expectedHeadOid']])
if (p/'lost-reply').exists():sys.exit(1)
print(json.dumps({'data':{'createCommitOnBranch':{'commit':{'oid':oid}}}}))
