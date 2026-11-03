# Test-only upgrade with synthetic corrupt/legacy accepted value; own VM only.
import hashlib,json,os,pathlib,socket,subprocess,time
p=pathlib.Path('/tmp/lease-gate'); token=p/'token';trust='op1=did:key:z6MktwupdmLXVVqTzCw4i46r4uGyosGXRnR3XjN4Zq7oMMsw'
addrs=['127.0.0.1:19601','127.0.0.2:19602','127.0.0.3:19603'];ports=','.join(addrs)
def env(wires,kind=None,addr=None):
 e=dict(os.environ,KEXE_ALLOW=wires,KEXE_CAP_RESOURCES_35=str(p))
 if kind:e['KEXE_CAP_RESOURCES_'+kind]=addr
 return e
def run(binary,args,envs,timeout=25):return subprocess.run([str(p/(binary+'.elf'))]+args,env=envs,capture_output=True,text=True,timeout=timeout)
ps=[];logs=[]
def stop():
 for q in ps:
  if q.poll() is None:q.terminate()
 for q in ps:
  try:q.wait(timeout=4)
  except subprocess.TimeoutExpired:q.kill();q.wait(timeout=3)
 ps.clear()
def start(binary):
 for i,addr in enumerate(addrs):
  f=(p/('upgrade-%s-%d.log'%(binary,i))).open('a');logs.append(f);d=p/('legacy%d'%i)
  q=subprocess.Popen([str(p/(binary+'.elf')),addr,str(d),'qwen-text',str(p/'topology.json'),trust,str(token)],env=env('7,35,37,38,43,44,46,47','43',addr),stdout=f,stderr=subprocess.STDOUT);ps.append(q)
  end=time.monotonic()+20;host,port=addr.split(':')
  while True:
   if q.poll() is not None:raise RuntimeError('group exited '+str(i))
   try:
    with socket.create_connection((host,int(port)),timeout=.3):break
   except OSError:
    if time.monotonic()>=end:raise RuntimeError('group not ready')
    time.sleep(.1)
try:
 for i in range(3):
  d=p/('legacy%d'%i);d.mkdir(mode=0o700);(d/'.epoch-lockfile').write_text('');os.chmod(d/'.epoch-lockfile',0o600)
  r=run('group',['init',str(d/'epoch-watermark')],env('35,37,38'));assert r.returncode==0,(r.stdout,r.stderr)
 cfg={'leaseFile':str(p/'badlease.json'),'node':'b70','task':'qwen-text','tokenFile':str(token),'voters':ports}
 config=p/'badconfig.json';config.write_text(json.dumps(cfg,sort_keys=True,separators=(',',':')))
 start('old-group');r=run('seed-bad',[str(config)],env('7,23,35,37,38,45,46,47','45',ports))
 assert r.returncode==0 and json.loads(r.stdout)['until']==-1,(r.returncode,r.stdout,r.stderr)
 stop()
 water=[(p/('legacy%d'%i)/'epoch-watermark').read_bytes() for i in range(3)]
 start('group');r=run('resident',[str(config)],env('7,23,35,37,38,45,46,47','45',ports))
 assert r.returncode!=0 and r.stdout.strip()=='no-lease',(r.returncode,r.stdout,r.stderr)
 assert not (p/'badlease.json').exists()
 assert all((p/('legacy%d'%i)/'epoch-watermark').read_bytes()==water[i] and not (p/('legacy%d'%i)/'epoch-2.state').exists() for i in range(3))
 print(json.dumps({'old_quorum':'chose-negative-expiry','upgraded_resident':'no-lease','successor_epoch':'not-created','lease_file':'absent'}),flush=True)
finally:
 stop()
 for f in logs:f.close()
