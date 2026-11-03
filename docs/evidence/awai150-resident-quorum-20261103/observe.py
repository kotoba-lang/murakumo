# Test-only: current native resident lease path and three disposable loopback groups.
import hashlib,json,os,pathlib,socket,subprocess,time
p=pathlib.Path('/tmp/lease-gate'); token=p/'token';token.write_text('a'*64);os.chmod(token,0o600)
trust='op1=did:key:z6MktwupdmLXVVqTzCw4i46r4uGyosGXRnR3XjN4Zq7oMMsw'; addrs=['127.0.0.1:19501','127.0.0.2:19502','127.0.0.3:19503'];ports=','.join(addrs)
def env(wires,kind=None,addr=None):
 e=dict(os.environ,KEXE_ALLOW=wires,KEXE_CAP_RESOURCES_35=str(p))
 if kind:e['KEXE_CAP_RESOURCES_'+kind]=addr
 return e
def run(binary,args,envs,timeout=25):return subprocess.run([str(p/(binary+'.elf'))]+args,env=envs,capture_output=True,text=True,timeout=timeout)
processes={};logs=[]
def stop(i):
 q=processes.pop(i,None)
 if q:
  if q.poll() is None:q.terminate()
  try:q.wait(timeout=4)
  except subprocess.TimeoutExpired:q.kill();q.wait(timeout=3)
def start(i):
 f=(p/('group%d.log'%i)).open('a');logs.append(f);d=p/('group%d'%i)
 q=subprocess.Popen([str(p/'group.elf'),addrs[i],str(d),'qwen-text',str(p/'topology.json'),trust,str(token)],env=env('7,35,37,38,43,44,46,47','43',addrs[i]),stdout=f,stderr=subprocess.STDOUT);processes[i]=q
 end=time.monotonic()+20;host,port=addrs[i].split(':')
 while True:
  if q.poll() is not None:raise RuntimeError('group exited '+str(i)+' '+(p/('group%d.log'%i)).read_text()[-300:])
  try:
   with socket.create_connection((host,int(port)),timeout=.3):break
  except OSError:
   if time.monotonic()>=end:raise RuntimeError('group not ready '+str(i))
   time.sleep(.1)
def resident():return run('resident',[str(p/'config.json')],env('7,23,35,37,38,45,46,47','45',ports))
try:
 for i in range(3):
  d=p/('group%d'%i);d.mkdir(mode=0o700);(d/'.epoch-lockfile').write_text('');os.chmod(d/'.epoch-lockfile',0o600)
  r=run('group',['init',str(d/'epoch-watermark')],env('35,37,38'));assert r.returncode==0,('init',i,r.stdout,r.stderr)
 cfg={'leaseFile':str(p/'lease.json'),'node':'b70','task':'qwen-text','tokenFile':str(token),'voters':ports}
 (p/'config.json').write_text(json.dumps(cfg,sort_keys=True,separators=(',',':')))
 for i in range(3):start(i)
 r=resident();assert r.returncode==0,('all',r.stdout,r.stderr)
 lease=json.loads(r.stdout);assert lease['node']=='b70' and lease['epoch']==1
 saved=(p/'lease.json').read_bytes();assert json.loads(saved)==lease
 print(json.dumps({'case':'three','result':'lease','epoch':lease['epoch'],'saved':True}),flush=True)
 stop(2);r=resident();assert r.returncode==0,('two',r.stdout,r.stderr)
 assert json.loads(r.stdout)==lease and (p/'lease.json').read_bytes()==saved
 print(json.dumps({'case':'two','result':'same-lease'}),flush=True)
 stop(1);r=resident();assert r.returncode!=0 and r.stdout.strip()=='no-lease',('one',r.returncode,r.stdout,r.stderr)
 assert (p/'lease.json').read_bytes()==saved
 print(json.dumps({'case':'one','result':'refused','saved_unchanged':True}),flush=True)
 start(1);r=resident();assert r.returncode==0,('rejoin',r.stdout,r.stderr)
 assert json.loads(r.stdout)==lease and (p/'lease.json').read_bytes()==saved
 print(json.dumps({'case':'rejoin','result':'same-lease'}),flush=True)
finally:
 for i in list(processes):stop(i)
 for f in logs:f.close()
