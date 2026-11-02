# One-off nonsecret GitHub CAS observation; not a production bot writer.
import hashlib,json,os,pathlib,subprocess,tempfile,uuid
repo='cloud-itonami/itonami-bot-state';remote='git@github.com:'+repo+'.git';branch='verification/cas-'+uuid.uuid4().hex;ref='refs/heads/'+branch
metadata=json.loads(subprocess.run(['gh','api','repos/'+repo,'--jq','{full_name,private,default_branch}'],check=True,capture_output=True,text=True,timeout=20).stdout)
if metadata!={'full_name':repo,'private':True,'default_branch':'main'}:raise RuntimeError('repository identity or privacy mismatch')
env=os.environ.copy()
for k in list(env):
 if k.startswith('GIT_'):env.pop(k)
env.update(GIT_CONFIG_NOSYSTEM='1',GIT_CONFIG_GLOBAL='/dev/null',GIT_TERMINAL_PROMPT='0')
def git(*args,check=True,cwd=None):return subprocess.run(['git','-c','core.hooksPath=/dev/null',*map(str,args)],cwd=cwd,env=env,capture_output=True,text=True,check=check,timeout=40)
def tip():
 out=git('ls-remote','--heads',remote,ref).stdout.strip()
 return out.split('\t')[0] if out else ''
def write(path,label):
 data=(json.dumps({'kind':'itonami.archive-concurrency-fixture/1','candidate':label},sort_keys=True,separators=(',',':'))+'\n').encode();path.write_bytes(data);return data
expected='';initial='';a_sha='';created=False
with tempfile.TemporaryDirectory(prefix='awai150-cas-work-') as tmp:
 root=pathlib.Path(tmp);a=root/'a';b=root/'b';v=root/'verify'
 try:
  for d in (a,b):
   git('clone','--quiet','--template=','--branch','main',remote,d)
   git('config','user.name','Itonami fixture',cwd=d);git('config','user.email','fixture@example.invalid',cwd=d)
  pa=git('rev-parse','HEAD',cwd=a).stdout.strip();pb=git('rev-parse','HEAD',cwd=b).stdout.strip()
  if pa!=pb:raise RuntimeError('different base commits')
  data=write(a/'cas-fixture.json','A');write(b/'cas-fixture.json','B')
  for d in (a,b):git('add','cas-fixture.json',cwd=d);git('commit','-q','-m','Nonsecret CAS fixture',cwd=d)
  a_sha=git('rev-parse','HEAD',cwd=a).stdout.strip();b_sha=git('rev-parse','HEAD',cwd=b).stdout.strip()
  initial=tip()
  if initial:raise RuntimeError('temporary ref already exists')
  git('push','--quiet',remote,'HEAD:'+ref,cwd=a);created=True
  rejected=git('push','--quiet',remote,'HEAD:'+ref,cwd=b,check=False)
  if rejected.returncode==0:raise RuntimeError('conflicting push unexpectedly accepted')
  reason='non-fast-forward' if 'non-fast-forward' in rejected.stderr.lower() else ('fetch first' if 'fetch first' in rejected.stderr.lower() else '')
  if not reason:raise RuntimeError('push failure was not identified as a ref conflict')
  if tip()!=a_sha:raise RuntimeError('remote ref does not match first write')
  git('init','--quiet','--bare','--template=',v);git('fetch','--quiet','--no-tags',remote,ref,cwd=v)
  fetched=git('rev-parse','FETCH_HEAD',cwd=v).stdout.strip();body=git('show','FETCH_HEAD:cas-fixture.json',cwd=v).stdout.encode()
  if fetched!=a_sha or body!=data:raise RuntimeError('independent readback mismatch')
  print(json.dumps({'repositoryPrivate':True,'baseCommit':pa,'firstCommit':a_sha,'secondCommit':b_sha,'secondPushExit':rejected.returncode,'rejectionReason':reason,'readbackCommitMatches':True,'readbackSha256':hashlib.sha256(body).hexdigest(),'testRef':ref}),flush=True)
 finally:
  # Only delete this test's own ref, and only if its value is still our first commit.
  observed=tip() if a_sha else ''
  if observed==a_sha and a_sha:
   git('push','--quiet','--force-with-lease='+ref+':'+a_sha,remote,':'+ref,cwd=a)
   if tip():raise RuntimeError('test ref still present after cleanup')
   print(json.dumps({'testRefRemoved':True}),flush=True)
  elif observed:
   raise RuntimeError('test ref changed; left intact for reconciliation')
