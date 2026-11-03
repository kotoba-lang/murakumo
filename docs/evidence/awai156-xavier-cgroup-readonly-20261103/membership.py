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
 remote='/usr/bin/python3 -c \'import os,pathlib,subprocess,json\nuid=os.getuid();r=subprocess.run(["/usr/bin/systemctl","show","user@"+str(uid)+".service","-p","MainPID","--value"],capture_output=True,text=True,timeout=5)\nif r.returncode or not r.stdout.strip().isdigit() or int(r.stdout.strip())<1: print(json.dumps({"status":"manager-pid-unknown"}));raise SystemExit\npid=int(r.stdout.strip());text=pathlib.Path("/proc/%d/cgroup"%pid).read_text();out={}\nfor line in text.splitlines():\n parts=line.split(":",2)\n if len(parts)!=3:continue\n for n in ("memory","cpu","pids"):\n  if n in parts[1].split(","):\n   d=pathlib.Path("/sys/fs/cgroup")/n/parts[2].lstrip("/");out[n]={"membership_reported":True,"path_exists":d.is_dir(),"path_writable":os.access(d,os.W_OK) if d.is_dir() else None}\nprint(json.dumps({"status":"observed","controllers":out}))\''
 cmd=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias=xavier','-o','UserKnownHostsFile='+f,'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+p['TailscaleIPs'][0],'xavier@xavier',remote]
 try:
  r=subprocess.run(cmd,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=12)
  t=r.stderr.lower();status='success' if r.returncode==0 else 'host-key-mismatch' if 'host key verification failed' in t or 'remote host identification has changed' in t else 'authentication-denied' if 'permission denied' in t else 'unavailable'
  print('status',status,'exit',r.returncode)
  if r.returncode==0:print(r.stdout.strip())
 except subprocess.TimeoutExpired:print('status timeout')
