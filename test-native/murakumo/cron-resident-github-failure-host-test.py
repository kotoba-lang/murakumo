# Disposable Linux VM only; public test keys and classified test fixture.
# GH_TOKEN must be provided privately via the environment, never an argument.
import base64,hashlib,json,os,pathlib,shutil,subprocess,sys,tempfile,time
if len(sys.argv)!=6:
 raise SystemExit("usage: cron-resident-github-failure-host-test.py BINARY_DIR ARCHIVE_FIXTURE_DIR SCRIPTS_DIR WORK_ORDER PRIVATE_OWNER_REPO")
bins,archive_fixture,scripts_dir,work_order=map(lambda x:pathlib.Path(x).resolve(),sys.argv[1:5]);repo=sys.argv[5]
base=pathlib.Path(tempfile.mkdtemp(prefix='awai147-archive-live-'));os.chmod(base,0o700);peerproc=None
try:
 store=base/'store';peerstore=base/'peer';store.mkdir();peerstore.mkdir();arch=archive_fixture;shutil.copytree(arch/'blocks',store,dirs_exist_ok=True)
 m=json.loads((arch/'manifest.json').read_text());root=m['root'];policy=m['policy'];environment=m['environment'];profile=m['profile'];task=m['task'];envbody=json.loads((store/environment).read_text())
 for key,value in [('a.key','9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60'),('b.key','4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb'),('token','0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef')]:
  (base/key).write_text(value+'\n');os.chmod(base/key,0o600)
 a='did:key:z6MktwupdmLXVVqTzCw4i46r4uGyosGXRnR3XjN4Zq7oMMsw';b='did:key:z6MkiaMbhXHNA4eJVCCj8dbzKzTgYDKf6crKgHVHid1F1WCT';address='127.0.0.1:29654'
 env=dict(os.environ,KEXE_ALLOW='7,20,23,35,37,38,43,44,45,46,47',KEXE_CAP_RESOURCES_35=str(base),KEXE_CAP_RESOURCES_20='/usr/bin/stat',KEXE_CAP_RESOURCES_43=address,KEXE_CAP_RESOURCES_45=address+',127.0.0.1:4801,127.0.0.2:4802,127.0.0.3:4803')
 def call(cmd,timeout=45):
  p=subprocess.run(list(map(str,cmd)),env=env,capture_output=True,text=True,timeout=timeout)
  if p.returncode:raise RuntimeError(('command-failed',pathlib.Path(str(cmd[0])).name,p.returncode,p.stdout[-500:],p.stderr[-500:]))
  return p.stdout.strip()
 def write(p,o):p.write_text(json.dumps(o,sort_keys=True,separators=(',',':')))
 def cid(p):return 'b'+base64.b32encode(b'\x01\x55\x12\x20'+hashlib.sha256(p.read_bytes()).digest()).decode().lower().rstrip('=')
 expired=int(time.time()*1000)-10000
 ra=call([bins/'fixture',store,root,base/'a.key','fixture-a',task,expired]).split();rb=call([bins/'fixture',store,root,base/'b.key','fixture-b',task,expired]).split();assert ra[0]==rb[0]=='receipt'
 head={'checkpoint':root,'kind':'itonami.resident-head/1','nextAt':9007199253000000,'receiptA':ra[1],'receiptB':rb[1],'request':'none','until':expired}
 runtime=dict(envbody,kind='itonami.readonly-profile-runtime-local-mtp/1',endpoint='http://localhost/v1/chat/completions',socket=str(base/'model.sock'),authorizationFile=str(base/'authorization'))
 write(base/'runtime',runtime)
 cfg={'archiveAck':str(base/'ack'),'authorA':a,'authorB':b,'bootstrap':root,'candidateHead':str(base/'candidate'),'domainA':'fixture-a','domainB':'fixture-b','environment':environment,'environmentFile':str(store/environment),'head':str(base/'head'),'intervalMs':86400000,'key':str(base/'b.key'),'kind':'itonami.profile-resident/1','node':'test-node','peer':address,'policy':policy,'policyFile':str(store/policy),'profile':profile,'request':str(base/'request'),'runtime':cid(base/'runtime'),'runtimeFile':str(base/'runtime'),'status':str(base/'status'),'store':str(store),'task':task,'tokenFile':str(base/'token'),'voters':'127.0.0.1:4801,127.0.0.2:4802,127.0.0.3:4803','modelArtifactDigest':envbody['modelArtifactDigest'],'mtpArtifactDigest':envbody['mtpArtifactDigest']}
 peer={'kind':'itonami.checkpoint-peer/1','listen':address,'key':str(base/'a.key'),'selfDid':a,'authorA':a,'authorB':b,'domain':'fixture-a','task':task,'profile':profile,'policy':policy,'environment':environment,'store':str(peerstore),'tokenFile':str(base/'token')}
 write(base/'head',head);write(base/'config',cfg);write(base/'peer-config',peer)
 peerproc=subprocess.Popen([str(bins/'peer'),str(base/'peer-config')],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 time.sleep(.5);assert peerproc.poll() is None
 assert call([bins/'resident','refresh',base/'config'])=='resident/replicas-refreshed'
 archiver=scripts_dir/'itonami-resident-github-archive.sh'
 result=call([archiver,bins/'resident',base/'config',bins/'checkpoint',base,repo],timeout=100)
 parts=result.split();assert len(parts)==3 and parts[0]=='resident-archive/confirmed' and parts[1]==root
 ack=json.loads((base/'ack').read_text());assert ack['checkpoint']==root and ack['tree']==parts[2] and ack['until']>int(time.time()*1000)
 assert call([bins/'resident','refresh',base/'config'])=='resident/retention-ready'
 # Exercise the actual cron controller and bridge against the genuine archive.
 source=base/'work.md';source.write_bytes(work_order.read_bytes())
 assert 'sha256:'+hashlib.sha256(source.read_bytes()).hexdigest()==envbody['workDigest']
 definition={'cron':'* * * * *','id':'mtp-bridge-fixture','input':cid(source),'kind':'itonami.cron-schedule/1','offsetMinutes':0,'policy':policy,'profile':profile,'rail':'murakumo'}
 write(base/'definition',definition); pin=cid(base/'definition');slot=(int(time.time()*1000)//60000-1)*60000
 cursor={'kind':'itonami.cron-cursor/1','schedule':pin,'slot':slot};write(base/'cursor',cursor);before=(base/'cursor').read_bytes()
 state=base/'controller';state.mkdir(mode=0o700)
 controller=scripts_dir/'itonami-cron-resident-once.sh'
 cmd=[controller,bins/'planner',bins/'bridge',bins/'resident',archiver,bins/'checkpoint',base,state,repo,base/'config',base/'definition',pin,base/'cursor',source]
 first=subprocess.run(list(map(str,cmd)),env=env,capture_output=True,text=True,timeout=120)
 assert first.returncode!=0 and 'cron-resident/completion-unknown' in first.stderr,(first.returncode,first.stdout,first.stderr[-300:])
 assert (base/'cursor').read_bytes()==before and (base/'request').exists()
 jobs=list((state/'j').iterdir());assert len(jobs)==1 and (jobs[0]/'dispatch').is_dir()
 log=(jobs[0]/'resident.log').read_text();err=(jobs[0]/'resident.err').read_text();assert 'resident/quorum-unavailable' in log,(log,err[-700:])
 second=subprocess.run(list(map(str,cmd)),env=env,capture_output=True,text=True,timeout=120)
 assert second.returncode!=0 and 'cron-resident/dispatch-unknown' in second.stderr,(second.returncode,second.stdout,second.stderr[-300:])
 assert (base/'cursor').read_bytes()==before and (jobs[0]/'resident.log').read_text()==log
 print(json.dumps({'expiredHead':'renewed','githubPrivateArchive':'independently-read-back','cronRequest':'prepared','resident':'quorum-unavailable','cursor':'unchanged','retry':'refused-uncertain-dispatch','checkpoint':root,'tree':parts[2],'sameVmTestSigners':True,'inference':False}),flush=True)
finally:
 if peerproc is not None:
  peerproc.terminate()
  try:peerproc.wait(timeout=5)
  except subprocess.TimeoutExpired:peerproc.kill();peerproc.wait()
 shutil.rmtree(base)
