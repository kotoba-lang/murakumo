# Disposable loopback test of two native resident lease paths, not production.
import concurrent.futures,json,os,pathlib,socket,subprocess,time
p=pathlib.Path('/tmp/lease-gate');token=p/'token';token.write_text('a'*64);os.chmod(token,0o600)
trust='op1=did:key:z6MktwupdmLXVVqTzCw4i46r4uGyosGXRnR3XjN4Zq7oMMsw';addrs=['127.0.0.1:19701','127.0.0.2:19702','127.0.0.3:19703'];ports=','.join(addrs)
def env(wires,kind=None,addr=None):
 e=dict(os.environ,KEXE_ALLOW=wires,KEXE_CAP_RESOURCES_35=str(p))
 if kind:e['KEXE_CAP_RESOURCES_'+kind]=addr
 return e
def run(binary,args,envs,timeout=35):return subprocess.run([str(p/(binary+'.elf'))]+args,env=envs,capture_output=True,text=True,timeout=timeout)
ps=[];logs=[]
def stop():
 for q in ps:
  if q.poll() is None:q.terminate()
 for q in ps:
  try:q.wait(timeout=4)
  except subprocess.TimeoutExpired:q.kill();q.wait(timeout=3)
 ps.clear()
def init(tag):
 for i in range(3):
  d=p/(tag+'-g%d'%i);d.mkdir(mode=0o700);(d/'.epoch-lockfile').write_text('');os.chmod(d/'.epoch-lockfile',0o600)
  r=run('group',['init',str(d/'epoch-watermark')],env('35,37,38'));assert r.returncode==0,(r.stdout,r.stderr)
def start(tag):
 for i,addr in enumerate(addrs):
  f=(p/(tag+'-%d.log'%i)).open('a');logs.append(f);d=p/(tag+'-g%d'%i)
  q=subprocess.Popen([str(p/'group.elf'),addr,str(d),'qwen-text',str(p/'topology.json'),trust,str(token)],env=env('7,35,37,38,43,44,46,47','43',addr),stdout=f,stderr=subprocess.STDOUT);ps.append(q)
  end=time.monotonic()+20;host,port=addr.split(':')
  while True:
   if q.poll() is not None:raise RuntimeError('group exited '+str(i))
   try:
    with socket.create_connection((host,int(port)),timeout=.3):break
   except OSError:
    if time.monotonic()>end:raise RuntimeError('group not ready')
    time.sleep(.1)
def config(tag,node):
 f=p/(tag+'-'+node+'.json');obj={'leaseFile':str(p/(tag+'-'+node+'-lease.json')),'node':node,'task':'qwen-text','tokenFile':str(token),'voters':ports};f.write_text(json.dumps(obj,sort_keys=True,separators=(',',':')));return f
def call(binary,cfg):return run(binary,[str(cfg)],env('7,23,35,37,38,45,46,47','45',ports))
try:
 init('race');start('race');a=config('race','b70');b=config('race','k16')
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as e:r=list(e.map(lambda f:call('resident',f),(a,b)))
 good=[json.loads(x.stdout) for x in r if x.returncode==0];failed=[x for x in r if x.returncode!=0]
 assert len(good)==1 and len(failed)==1 and failed[0].stdout.strip()=='no-lease',[(x.returncode,x.stdout,x.stderr[-200:]) for x in r]
 winner=good[0]['node'];loser='k16' if winner=='b70' else 'b70';assert good[0]['epoch']==1
 retry=call('resident',b if loser=='k16' else a);assert retry.returncode!=0 and retry.stdout.strip()=='no-lease'
 print(json.dumps({'case':'concurrent','lease_count':len(good),'winner':winner,'loser_retry':'no-lease'}),flush=True)
 stop();init('takeover');start('takeover');a=config('takeover','b70');b=config('takeover','k16')
 seeded=call('seed-expired',a);assert seeded.returncode==0,('seed',seeded.stdout,seeded.stderr)
 old=json.loads(seeded.stdout);assert old['node']=='b70' and old['epoch']==1
 acquired=call('resident',b);assert acquired.returncode==0,('takeover',acquired.stdout,acquired.stderr)
 new=json.loads(acquired.stdout);assert new['node']=='k16' and new['epoch']==2
 previous=call('resident',a);assert previous.returncode!=0 and previous.stdout.strip()=='no-lease',('previous',previous.stdout,previous.stderr)
 assert all((p/('takeover-g%d'%i)/'epoch-2.state').exists() for i in range(3))
 print(json.dumps({'case':'expired-takeover','previous':'b70','new_holder':'k16','new_epoch':2,'previous_retry':'no-lease'}),flush=True)
finally:
 stop()
 for f in logs:f.close()
