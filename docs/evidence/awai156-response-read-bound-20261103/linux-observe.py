import base64,hashlib,json,os,pathlib,shlex,subprocess,tempfile
compiler=pathlib.Path('/private/tmp/awai151-fifo-linux-v7/root.elf').read_bytes();fixture=pathlib.Path('/Users/junkawasaki/github/workspaces/codex/awai150-inference-replay-guard/docs/evidence/awai156-response-read-bound-20261103/fixture.kotoba').read_bytes()
payload=json.dumps({'compiler':base64.b64encode(compiler).decode(),'fixture':base64.b64encode(fixture).decode(),'compiler_sha256':hashlib.sha256(compiler).hexdigest(),'fixture_sha256':hashlib.sha256(fixture).hexdigest()}).encode()
s=json.loads(subprocess.check_output(['tailscale','status','--json']));p=next(x for x in s['Peer'].values() if x.get('HostName')=='xavier');assert p.get('Online') and p.get('sshHostKeys')
remote=r'''import base64,hashlib,json,os,pathlib,resource,subprocess,sys,tempfile
p=json.load(sys.stdin);c=base64.b64decode(p['compiler']);f=base64.b64decode(p['fixture']);assert hashlib.sha256(c).hexdigest()==p['compiler_sha256'] and hashlib.sha256(f).hexdigest()==p['fixture_sha256'];out={'compiler_sha256':p['compiler_sha256'],'fixture_sha256':p['fixture_sha256']}
def limits():
 resource.setrlimit(resource.RLIMIT_CPU,(5,5));resource.setrlimit(resource.RLIMIT_FSIZE,(16*1024*1024,16*1024*1024));resource.setrlimit(resource.RLIMIT_CORE,(0,0))
with tempfile.TemporaryDirectory(prefix='awai156-fs2-') as d:
 root=pathlib.Path(d); exe=root/'compiler';exe.write_bytes(c);exe.chmod(0o700);(root/'fixture.kotoba').write_bytes(f)
 env={'PATH':'/usr/bin:/bin','KEXE_CAP_RESOURCES_35':d}
 for label,args in [('compile',['compile','fixture.kotoba','--target','aarch64-linux','--output','fixture.elf','--format','static'])]:
  try:
   r=subprocess.run([str(exe)]+args,cwd=root,capture_output=True,text=True,timeout=8,preexec_fn=limits,env=env);out[label]={'exit':r.returncode,'stdout':r.stdout[-1000:],'stderr':r.stderr[-1000:]}
  except subprocess.TimeoutExpired:out[label]={'timeout':True}

 if (root/'fixture.elf').exists() and out['compile'].get('exit')==0:
  binary=root/'fixture.elf';binary.chmod(0o700);out['artifact_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest();env['KEXE_ALLOW']='35,37,38';rows=[]
  for name,n in [('empty',0),('small',1024),('at-limit',262144),('over-limit',262145),('large',8388608)]:
   path=root/name
   with open(path,'wb') as z:z.truncate(n)
   r=subprocess.run([str(binary),str(path)],cwd=root,env=env,capture_output=True,text=True,timeout=8,preexec_fn=limits);rows.append({'case':name,'exit':r.returncode,'stdout':r.stdout[-500:],'stderr':r.stderr[-500:]})
  for name in ('missing','directory'):
   path=root/name
   if name=='directory':path.mkdir()
   r=subprocess.run([str(binary),str(path)],cwd=root,env=env,capture_output=True,text=True,timeout=8,preexec_fn=limits);rows.append({'case':name,'exit':r.returncode,'stdout':r.stdout[-500:],'stderr':r.stderr[-500:]})
  out['rows']=rows
out['task_dir_removed']=not root.exists();print(json.dumps(out))'''
with tempfile.TemporaryDirectory(prefix='awai156-hostkeys-') as d:
 k=pathlib.Path(d)/'known';k.write_text(''.join('xavier '+x+'\n' for x in p['sshHostKeys']));os.chmod(k,0o600)
 cmd=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias=xavier','-o','UserKnownHostsFile='+str(k),'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+p['TailscaleIPs'][0],'xavier@xavier','/usr/bin/python3 -c '+shlex.quote(remote)]
 r=subprocess.run(cmd,input=payload,capture_output=True,timeout=25);print('ssh_exit',r.returncode,'stdout',r.stdout.decode(),'stderr',r.stderr.decode()[:500])
