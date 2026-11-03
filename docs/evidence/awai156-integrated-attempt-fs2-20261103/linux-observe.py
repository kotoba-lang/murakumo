import base64,hashlib,json,os,pathlib,shlex,subprocess,tempfile
compiler=pathlib.Path('/private/tmp/awai154-linux-existence-root.elf').read_bytes();fixture=pathlib.Path('/private/tmp/awai156-job-live-fs2/fixture.kotoba').read_bytes()
payload=json.dumps({'compiler':base64.b64encode(compiler).decode(),'fixture':base64.b64encode(fixture).decode(),'compiler_sha256':hashlib.sha256(compiler).hexdigest(),'fixture_sha256':hashlib.sha256(fixture).hexdigest()}).encode()
s=json.loads(subprocess.check_output(['tailscale','status','--json']));peer=next(x for x in s['Peer'].values() if x.get('HostName')=='xavier');assert peer.get('Online') and peer.get('sshHostKeys')
remote=r'''import base64,hashlib,json,os,pathlib,resource,subprocess,sys,tempfile
p=json.load(sys.stdin);c=base64.b64decode(p['compiler']);f=base64.b64decode(p['fixture']);assert hashlib.sha256(c).hexdigest()==p['compiler_sha256'] and hashlib.sha256(f).hexdigest()==p['fixture_sha256'];out={'compiler_sha256':p['compiler_sha256'],'fixture_sha256':p['fixture_sha256']}
def limits():
 resource.setrlimit(resource.RLIMIT_CPU,(8,8));resource.setrlimit(resource.RLIMIT_FSIZE,(16*1024*1024,16*1024*1024));resource.setrlimit(resource.RLIMIT_CORE,(0,0))
with tempfile.TemporaryDirectory(prefix='awai156-live-attempt-') as d:
 root=pathlib.Path(d);exe=root/'compiler';exe.write_bytes(c);exe.chmod(0o700);(root/'fixture.kotoba').write_bytes(f);env={'PATH':'/usr/bin:/bin','KEXE_CAP_RESOURCES_35':d}
 r=subprocess.run([str(exe),'compile','fixture.kotoba','--target','aarch64-linux','--output','fixture.elf','--format','static'],cwd=root,env=env,capture_output=True,text=True,timeout=12,preexec_fn=limits);out['compile']={'exit':r.returncode,'stdout':r.stdout[-500:],'stderr':r.stderr[-500:]}
 if r.returncode==0:
  b=root/'fixture.elf';b.chmod(0o700);out['artifact_sha256']=hashlib.sha256(b.read_bytes()).hexdigest();env['KEXE_ALLOW']='35,37,38';attempt=root/'attempt';attempt.mkdir(mode=0o700);parallel=root/'parallel';parallel.mkdir(mode=0o700)
  def invoke(path):
   q=subprocess.run([str(b),'source',str(path)],cwd=root,env=env,capture_output=True,text=True,timeout=8,preexec_fn=limits);return {'exit':q.returncode,'cell':('fixture/input:validated-source' in q.stdout.splitlines()),'receipt':any(x.startswith('receipt ') for x in q.stdout.splitlines()),'duplicate':('job/attempt-exists-or-unknown' in q.stdout.splitlines()),'err':q.stderr[-200:]}
  out['first']=invoke(attempt);out['repeat']=invoke(attempt)
  procs=[subprocess.Popen([str(b),'source',str(parallel)],cwd=root,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,preexec_fn=limits) for _ in range(4)];rows=[]
  for q in procs:
   try:o,e=q.communicate(timeout=8)
   except subprocess.TimeoutExpired:q.kill();o,e=q.communicate(timeout=3)
   rows.append({'exit':q.returncode,'cell':('fixture/input:validated-source' in o.splitlines()),'receipt':any(x.startswith('receipt ') for x in o.splitlines()),'duplicate':('job/attempt-exists-or-unknown' in o.splitlines()),'err':e[-200:]})
  out['concurrent']=rows
out['task_dir_removed']=not root.exists();print(json.dumps(out))'''
with tempfile.TemporaryDirectory(prefix='awai156-hostkeys-') as d:
 k=pathlib.Path(d)/'known';k.write_text(''.join('xavier '+x+'\n' for x in peer['sshHostKeys']));os.chmod(k,0o600)
 cmd=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias=xavier','-o','UserKnownHostsFile='+str(k),'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+peer['TailscaleIPs'][0],'xavier@xavier','/usr/bin/python3 -c '+shlex.quote(remote)]
 r=subprocess.run(cmd,input=payload,capture_output=True,timeout=45);print('ssh_exit',r.returncode,'stdout',r.stdout.decode(),'stderr',r.stderr.decode()[:500]);assert r.returncode==0
