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
 remote='/usr/bin/python3 -c \'import os,json,subprocess,pathlib\nroot=pathlib.Path("/sys/fs/cgroup");r=subprocess.run(["/usr/bin/systemctl","--user","show","-p","ControlGroup","--value"],capture_output=True,text=True,timeout=4);cg=r.stdout.strip() if r.returncode==0 else ""\nout={"manager_cgroup_observed":bool(cg.startswith("/")),"v2_controllers":None,"v1":{}}\nf=root/"cgroup.controllers"\nif f.is_file():out["v2_controllers"]=f.read_text().strip().split()\nfor n in ("memory","cpu","pids"):\n base=root/n\n target=base/cg.lstrip("/") if cg.startswith("/") else base/"__unknown__"\n out["v1"][n]={"mount_present":base.is_dir(),"manager_dir_present":target.is_dir(),"manager_dir_writable":os.access(target,os.W_OK) if target.is_dir() else None}\nprint(json.dumps(out))\''
 cmd=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias=xavier','-o','UserKnownHostsFile='+f,'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+p['TailscaleIPs'][0],'xavier@xavier',remote]
 try:
  r=subprocess.run(cmd,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=12)
  t=r.stderr.lower();status='success' if r.returncode==0 else 'host-key-mismatch' if 'host key verification failed' in t or 'remote host identification has changed' in t else 'authentication-denied' if 'permission denied' in t else 'unavailable'
  print('status',status,'exit',r.returncode)
  if r.returncode==0:print(r.stdout.strip())
 except subprocess.TimeoutExpired:print('status timeout')
