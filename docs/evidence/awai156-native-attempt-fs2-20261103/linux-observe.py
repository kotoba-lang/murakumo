# Isolated synthetic FS2 marker test; no existing service or workload.
import base64,hashlib,json,os,pathlib,shlex,subprocess,tempfile
compiler=pathlib.Path('/private/tmp/awai151-fifo-linux-v7/root.elf').read_bytes();fixture=pathlib.Path('/private/tmp/awai156-attempt-fs2/fixture.kotoba').read_bytes()
payload=json.dumps({'compiler':base64.b64encode(compiler).decode(),'fixture':base64.b64encode(fixture).decode(),'compiler_sha256':hashlib.sha256(compiler).hexdigest(),'fixture_sha256':hashlib.sha256(fixture).hexdigest()}).encode()
s=json.loads(subprocess.check_output(['tailscale','status','--json']));p=next(x for x in s['Peer'].values() if x.get('HostName')=='xavier');assert p.get('Online') and p.get('sshHostKeys')
remote=r'''import base64,hashlib,json,os,pathlib,resource,subprocess,sys,tempfile
p=json.load(sys.stdin);c=base64.b64decode(p['compiler']);f=base64.b64decode(p['fixture']);assert hashlib.sha256(c).hexdigest()==p['compiler_sha256'] and hashlib.sha256(f).hexdigest()==p['fixture_sha256'];out={'compiler_sha256':p['compiler_sha256'],'fixture_sha256':p['fixture_sha256']}
def limits():
 resource.setrlimit(resource.RLIMIT_CPU,(5,5));resource.setrlimit(resource.RLIMIT_FSIZE,(16*1024*1024,16*1024*1024));resource.setrlimit(resource.RLIMIT_CORE,(0,0))
with tempfile.TemporaryDirectory(prefix='awai156-attempt-') as d:
 root=pathlib.Path(d);exe=root/'compiler';exe.write_bytes(c);exe.chmod(0o700);(root/'fixture.kotoba').write_bytes(f);env={'PATH':'/usr/bin:/bin','KEXE_CAP_RESOURCES_35':d}
 r=subprocess.run([str(exe),'compile','fixture.kotoba','--target','aarch64-linux','--output','fixture.elf','--format','static'],cwd=root,env=env,capture_output=True,text=True,timeout=10,preexec_fn=limits);out['compile']={'exit':r.returncode,'stdout':r.stdout[-500:],'stderr':r.stderr[-500:]}
 if r.returncode==0:
  b=root/'fixture.elf';b.chmod(0o700);out['artifact_sha256']=hashlib.sha256(b.read_bytes()).hexdigest();env['KEXE_ALLOW']='35,37,38';private=root/'private';private.mkdir(mode=0o700);public=root/'public';public.mkdir(mode=0o755);os.chmod(public,0o755);(root/'link').symlink_to(private,target_is_directory=True);special=root/'special';special.mkdir(mode=0o700);os.chmod(special,0o1700)
  def run(path,jid):
   r=subprocess.run([str(b),str(path),jid],cwd=root,env=env,capture_output=True,text=True,timeout=8,preexec_fn=limits);return {'exit':r.returncode,'out':r.stdout.strip(),'err':r.stderr[-200:]}
  out['first']=run(private,'a'*64);out['duplicate']=run(private,'a'*64);out['public']=run(public,'b'*64);out['symlink']=run(root/'link','c'*64);out['special']=run(special,'e'*64);out['bad_id']=run(private,'bad');out['mode']=(oct((private/('a'*64)).stat().st_mode&0o7777) if (private/('a'*64)).exists() else None)
  procs=[subprocess.Popen([str(b),str(private),'d'*64],cwd=root,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,preexec_fn=limits) for _ in range(4)];rows=[]
  for q in procs:
   try:o,e=q.communicate(timeout=8)
   except subprocess.TimeoutExpired:q.kill();o,e=q.communicate(timeout=3)
   rows.append({'exit':q.returncode,'out':o.strip(),'err':e[-200:]})
  out['concurrent']=rows
out['task_dir_removed']=not root.exists();print(json.dumps(out))'''
with tempfile.TemporaryDirectory(prefix='awai156-hostkeys-') as d:
 k=pathlib.Path(d)/'known';k.write_text(''.join('xavier '+x+'\n' for x in p['sshHostKeys']));os.chmod(k,0o600)
 cmd=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias=xavier','-o','UserKnownHostsFile='+str(k),'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+p['TailscaleIPs'][0],'xavier@xavier','/usr/bin/python3 -c '+shlex.quote(remote)]
 r=subprocess.run(cmd,input=payload,capture_output=True,timeout=40);print('ssh_exit',r.returncode,'stdout',r.stdout.decode(),'stderr',r.stderr.decode()[:500]);assert r.returncode==0
