# Run only in a disposable Linux VM with public test identities. Arguments:
# BINARY_DIR FIXTURE_TAR. The binaries are static resident, peer, retain, and
# fixture executables from the pinned source-unity build. Never use live keys.
import json,os,pathlib,shutil,subprocess,sys,tarfile,tempfile,time
if len(sys.argv) != 3:
 raise SystemExit("usage: resident-retention-live-host-test.py BINARY_DIR FIXTURE_TAR")
bin=pathlib.Path(sys.argv[1]).resolve();fixture_tar=pathlib.Path(sys.argv[2]).resolve()
base=pathlib.Path(tempfile.mkdtemp(prefix='awai147-refresh-live-'));os.chmod(base,0o700)
peerproc=None
try:
 store=base/'store';peerstore=base/'peer';store.mkdir();peerstore.mkdir()
 with tarfile.open(fixture_tar) as t:t.extractall(store,filter='data')
 for key,value in [('a.key','9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60'),('b.key','4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb'),('token','0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef')]:
  (base/key).write_text(value+'\n');os.chmod(base/key,0o600)
 root='bafkreicv5ltxgppreijv3gcsz3tjlx54i2dzsrsf4sg43ltw2d2n7agq7a'; policy='bafkreifghssriaiiwgjxm6c6nzkaczgfm45zsb6ptsjp3laeyue67set64'; environment='bafkreia27omuz5t5nszzi2t2clsddenidxxz5zs52ssug25oqgrm53yk2y';profile='sha256:941c3bd41b58d3e460fd913fd2745cc23492f7d524939fbd313990b77449aca5'
 a='did:key:z6MktwupdmLXVVqTzCw4i46r4uGyosGXRnR3XjN4Zq7oMMsw';b='did:key:z6MkiaMbhXHNA4eJVCCj8dbzKzTgYDKf6crKgHVHid1F1WCT';address='127.0.0.1:29654';task='awai147-test' 
 env=dict(os.environ,KEXE_ALLOW='7,20,23,35,37,38,43,44,45,46,47',KEXE_CAP_RESOURCES_35=str(base),KEXE_CAP_RESOURCES_20='/usr/bin/stat',KEXE_CAP_RESOURCES_43=address,KEXE_CAP_RESOURCES_45=address)
 def call(binary,*args):
  p=subprocess.run([str(bin/binary),*map(str,args)],env=env,capture_output=True,text=True,timeout=30)
  if p.returncode:raise RuntimeError((binary,p.returncode,p.stdout,p.stderr))
  return p.stdout.strip()
 expired=int(time.time()*1000)-10000
 ra=call('fixture',store,root,base/'a.key','fixture-a',task,expired).split();rb=call('fixture',store,root,base/'b.key','fixture-b',task,expired).split();assert ra[0]==rb[0]=='receipt' and len(ra)==len(rb)==2
 head={'checkpoint':root,'kind':'itonami.resident-head/1','nextAt':9007199253000000,'receiptA':ra[1],'receiptB':rb[1],'request':'none','until':expired}
 cfg={'authorA':a,'authorB':b,'bootstrap':root,'candidateHead':str(base/'candidate'),'domainA':'fixture-a','domainB':'fixture-b','environment':environment,'environmentFile':str(store/environment),'head':str(base/'head'),'intervalMs':86400000,'key':str(base/'b.key'),'kind':'itonami.profile-resident/1','node':'test-node','peer':address,'policy':policy,'policyFile':str(store/policy),'profile':profile,'request':str(base/'request'),'status':str(base/'status'),'store':str(store),'task':task,'tokenFile':str(base/'token'),'voters':'127.0.0.1:4801,127.0.0.2:4802,127.0.0.3:4803'}
 peer={'kind':'itonami.checkpoint-peer/1','listen':address,'key':str(base/'a.key'),'selfDid':a,'authorA':a,'authorB':b,'domain':'fixture-a','task':task,'profile':profile,'policy':policy,'environment':environment,'store':str(peerstore),'tokenFile':str(base/'token')}
 def write(p,o):p.write_text(json.dumps(o,sort_keys=True,separators=(',',':')))
 write(base/'head',head);write(base/'config',cfg);write(base/'peer-config',peer);original=(base/'head').read_bytes()
 invalid=dict(head,receiptA=root);write(base/'head',invalid)
 result=call('resident','refresh',base/'config');assert result=='resident/head-refused',result
 write(base/'head',head)
 result=call('resident','refresh',base/'config');assert result=='resident/replica-unavailable',result
 assert (base/'head').read_bytes()==original
 peerproc=subprocess.Popen([str(bin/'peer'),str(base/'peer-config')],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 time.sleep(.5);assert peerproc.poll() is None,'peer stopped'
 result=call('resident','refresh',base/'config');assert result=='resident/replicas-refreshed',result
 refreshed=json.loads((base/'head').read_text());assert refreshed['checkpoint']==root and refreshed['request']=='none' and refreshed['nextAt']==head['nextAt'] and refreshed['until']>int(time.time()*1000)+3600000
 assert refreshed['receiptA']!=head['receiptA'] and refreshed['receiptB']!=head['receiptB']
 pins={'authorA':a,'authorB':b,'checkpoint':root,'domainA':'fixture-a','domainB':'fixture-b','task':task,'until':refreshed['until']};write(base/'pins',pins)
 result=call('retain','verify-pair',store/refreshed['receiptA'],store/refreshed['receiptB'],base/'pins');assert result=='retention/pair-verified',result
 current=(base/'head').read_bytes();result=call('resident','refresh',base/'config');assert result=='resident/retention-ready' and (base/'head').read_bytes()==current
 assert not (base/'request').exists()
 print(json.dumps({'invalidSignedHead':'refused','peerUnavailable':'refused-head-preserved','expiredHead':'renewed','newSignatures':'verified','request':'absent','repeat':'unchanged','twoSigners':'public-rfc8032-test-vectors','replicaFailureDomains':'one-linux-vm','github':False,'inference':False}),flush=True)
finally:
 if peerproc is not None:
  peerproc.terminate()
  try:peerproc.wait(timeout=5)
  except subprocess.TimeoutExpired:peerproc.kill();peerproc.wait()
 shutil.rmtree(base)
