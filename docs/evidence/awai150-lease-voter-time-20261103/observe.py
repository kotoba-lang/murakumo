# Disposable loopback fixture with public test key and synthetic token only.
import json,os,pathlib,socket,subprocess,time
p=pathlib.Path('/tmp/voter-gate'); token=p/'token';token.write_text('a'*64);os.chmod(token,0o600)
trust='op1=did:key:z6MktwupdmLXVVqTzCw4i46r4uGyosGXRnR3XjN4Zq7oMMsw'; now=1792500000000
ports=[19401,19402,19403]; allowed=','.join('127.0.0.1:'+str(x) for x in ports)
def env(wires,kind=None,addr=None):
 d=dict(os.environ,KEXE_ALLOW=wires,KEXE_CAP_RESOURCES_35=str(p))
 if kind:d['KEXE_CAP_RESOURCES_'+kind]=addr
 return d
def run(exe,args,envs):
 return subprocess.run([str(p/(exe+'.elf'))]+args,env=envs,capture_output=True,text=True,timeout=18)
def case(name,exe,expiry,expected):
 d=p/name;d.mkdir(); ps=[]; logs=[]
 try:
  for i,port in enumerate(ports):
   path=d/('v%d.state'%i)
   r=run(exe,['init-fresh',str(path)],env('35,37,38'));assert r.returncode==0,(name,'init',r.stdout,r.stderr)
   f=(d/('v%d.log'%i)).open('w+');logs.append(f)
   args=['voter',str(port),str(path),'20000',str(p/'topology.json'),trust,'1',str(now),str(token)]
   ps.append(subprocess.Popen([str(p/(exe+'.elf'))]+args,env=env('7,35,37,38,43,44,46,47','43','127.0.0.1:'+str(port)),stdout=f,stderr=subprocess.STDOUT))
  end=time.monotonic()+20
  for port in ports:
   while True:
    assert all(x.poll() is None for x in ps),(name,'voter-exited')
    try:
     with socket.create_connection(('127.0.0.1',port),timeout=.3):break
    except OSError:
     if time.monotonic()>=end:raise RuntimeError('voter not ready')
     time.sleep(.1)
  value=json.dumps({'class':'qwen-text','epoch':1,'node':'b70','revision':7,'slot':0,'until':expiry},sort_keys=True,separators=(',',':'))
  r=run(exe,['proposer','full','101','b70',value,'token='+str(token)]+[str(x) for x in ports],env('7,23,35,37,38,45,46,47','45',allowed))
  assert expected in r.stdout,(name,'proposer',r.returncode,r.stdout,r.stderr)
  print(json.dumps({'case':name,'returncode':r.returncode,'expected':expected,'stdout':r.stdout.strip()[:450]}),flush=True)
 finally:
  for q in ps:
   if q.poll() is None:q.terminate()
  for q in ps:
   try:q.wait(timeout=4)
   except subprocess.TimeoutExpired:q.kill();q.wait(timeout=3)
  for f in logs:f.close()
for args in [('new-negative','current',-1,'no-majority accept 0'),('old-negative','old-core',-1,'chosen '),('expired-zero','current',0,'chosen '),('current-valid','current',now+900000,'chosen ')]:case(*args)
