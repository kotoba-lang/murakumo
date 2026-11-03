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
 remote='/usr/bin/python3 -c \'import subprocess,uuid,json,time\nunit="awai156-probe-"+uuid.uuid4().hex+".service"\ncmd=["/usr/bin/systemd-run","--user","--quiet","--no-block","--collect","--unit="+unit,"--property=Type=simple","--property=MemoryMax=64M","--property=CPUQuota=25%","--property=RuntimeMaxSec=5s","/usr/bin/sleep","3"]\nresult={"case":"bounded-transient-probe"}\ntry:\n r=subprocess.run(cmd,capture_output=True,text=True,timeout=5);result.update(start_exit=r.returncode,start_stderr=r.stderr.strip()[:500])\n q=subprocess.run(["/usr/bin/systemctl","--user","show",unit,"-p","LoadState","-p","ActiveState","-p","SubState","-p","MemoryMax","-p","CPUQuotaPerSecUSec","-p","ControlGroup","-p","Result"],capture_output=True,text=True,timeout=5);result.update(show_exit=q.returncode,properties=q.stdout.strip().splitlines())\n if r.returncode==0:time.sleep(4)\nfinally:\n q=subprocess.run(["/usr/bin/systemctl","--user","show",unit,"-p","LoadState","-p","ActiveState"],capture_output=True,text=True,timeout=5)\n result["after"]=q.stdout.strip().splitlines();result["after_exit"]=q.returncode\n if "ActiveState=active" in result["after"] or "ActiveState=activating" in result["after"]:\n  c=subprocess.run(["/usr/bin/systemctl","--user","stop",unit],capture_output=True,text=True,timeout=5);result["cleanup_stop_exit"]=c.returncode\n  z=subprocess.run(["/usr/bin/systemctl","--user","show",unit,"-p","LoadState","-p","ActiveState"],capture_output=True,text=True,timeout=5);result["after_cleanup"]=z.stdout.strip().splitlines()\nprint(json.dumps(result))\''
 cmd=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias=xavier','-o','UserKnownHostsFile='+f,'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+p['TailscaleIPs'][0],'xavier@xavier',remote]
 try:
  r=subprocess.run(cmd,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=12)
  t=r.stderr.lower();status='success' if r.returncode==0 else 'host-key-mismatch' if 'host key verification failed' in t or 'remote host identification has changed' in t else 'authentication-denied' if 'permission denied' in t else 'unavailable'
  print('status',status,'exit',r.returncode)
  if r.returncode==0:print(r.stdout.strip())
 except subprocess.TimeoutExpired:print('status timeout')
