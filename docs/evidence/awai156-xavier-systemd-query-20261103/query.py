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
 remote='/usr/bin/python3 -c \'import subprocess,json,uuid\nu="itonami-recovery-observe-"+uuid.uuid4().hex+".service"\ncases=[("missing-load",u,"LoadState"),("existing-load","block-ingest-upnp.service","LoadState"),("existing-sub","block-ingest-upnp.service","SubState")]\nfor label,unit,prop in cases:\n r=subprocess.run(["/usr/bin/systemctl","--user","show","--property="+prop,"--value",unit],capture_output=True,text=True,timeout=5)\n print(json.dumps({"case":label,"exit":r.returncode,"stdout":r.stdout.strip(),"stderr_nonempty":bool(r.stderr)}))\''
 cmd=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias=xavier','-o','UserKnownHostsFile='+f,'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+p['TailscaleIPs'][0],'xavier@xavier',remote]
 try:
  r=subprocess.run(cmd,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=12)
  t=r.stderr.lower();status='success' if r.returncode==0 else 'host-key-mismatch' if 'host key verification failed' in t or 'remote host identification has changed' in t else 'authentication-denied' if 'permission denied' in t else 'unavailable'
  print('status',status,'exit',r.returncode)
  if r.returncode==0:print(r.stdout.strip())
 except subprocess.TimeoutExpired:print('status timeout')
