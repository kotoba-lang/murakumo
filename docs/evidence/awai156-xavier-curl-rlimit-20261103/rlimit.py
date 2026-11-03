import json,os,subprocess,tempfile
s=json.loads(subprocess.check_output(['tailscale','status','--json'],timeout=8));p=next(x for x in s['Peer'].values() if x.get('HostName')=='xavier')
keys=p.get('sshHostKeys') or []
if not p.get('Online') or not p.get('TailscaleIPs') or not keys:raise SystemExit('identity or connectivity unavailable')
with tempfile.TemporaryDirectory(prefix='awai156-hostkeys-') as d:
 f=os.path.join(d,'known_hosts')
 with open(f,'x') as out:
  os.chmod(f,0o600)
  for key in keys:
   assert key.startswith(('ssh-ed25519 ','ssh-rsa ','ecdsa-sha2-nistp256 ')) and '\n' not in key
   out.write('xavier '+key+'\n')
 remote='/usr/bin/python3 -c \'import subprocess,uuid,json\nunit="awai156-rlimit-"+uuid.uuid4().hex+".service";out={}\ncmd=["/usr/bin/systemd-run","--user","--quiet","--wait","--collect","--pipe","--unit="+unit,"--property=Type=oneshot","--property=LimitAS=64M","--property=LimitCPU=3s","--property=TimeoutStartSec=8s","/usr/bin/python3","-c",\'"\'"\'import json,mmap,resource\\nout={"as_limit":resource.getrlimit(resource.RLIMIT_AS),"cpu_limit":resource.getrlimit(resource.RLIMIT_CPU)}\\nfor label,size in (("small",8*1024*1024),("large",96*1024*1024)):\\n try:\\n  m=mmap.mmap(-1,size);m[0:1]=b"x";m[-1:]=b"x";out[label]="allocated";m.close()\\n except (OSError,MemoryError) as e:out[label]=type(e).__name__;out[label+"_errno"]=getattr(e,"errno",None)\\nprint(json.dumps(out),flush=True)\'"\'"\']\ntry:\n r=subprocess.run(cmd,capture_output=True,text=True,timeout=12);out={"exit":r.returncode,"stdout":r.stdout.strip(),"stderr":r.stderr.strip()[:500]}\nexcept subprocess.TimeoutExpired:out={"status":"command-timeout"}\nfinally:\n q=subprocess.run(["/usr/bin/systemctl","--user","show",unit,"-p","LoadState","-p","ActiveState"],capture_output=True,text=True,timeout=5);out["after"]=q.stdout.strip().splitlines()\n if "ActiveState=active" in out["after"] or "ActiveState=activating" in out["after"]:\n  z=subprocess.run(["/usr/bin/systemctl","--user","stop",unit],capture_output=True,text=True,timeout=5);out["cleanup_stop_exit"]=z.returncode\n  q=subprocess.run(["/usr/bin/systemctl","--user","show",unit,"-p","LoadState","-p","ActiveState"],capture_output=True,text=True,timeout=5);out["after_cleanup"]=q.stdout.strip().splitlines()\nprint(json.dumps(out))\''
 cmd=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias=xavier','-o','UserKnownHostsFile='+f,'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+p['TailscaleIPs'][0],'xavier@xavier',remote]
 try:
  r=subprocess.run(cmd,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=12)
  t=r.stderr.lower();status='success' if r.returncode==0 else 'host-key-mismatch' if 'host key verification failed' in t or 'remote host identification has changed' in t else 'authentication-denied' if 'permission denied' in t else 'unavailable'
  print('status',status,'exit',r.returncode)
  if r.returncode==0:print(r.stdout.strip())
 except subprocess.TimeoutExpired:print('status timeout')
