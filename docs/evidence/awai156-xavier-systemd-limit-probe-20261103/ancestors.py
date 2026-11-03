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
 remote='/usr/bin/python3 -c \'import subprocess,uuid,json,time,pathlib\nunit="awai156-anc-"+uuid.uuid4().hex+".service";out={}\ntry:\n r=subprocess.run(["/usr/bin/systemd-run","--user","--quiet","--no-block","--collect","--unit="+unit,"--property=Type=simple","--property=MemoryMax=64M","--property=CPUQuota=25%","--property=RuntimeMaxSec=5s","/usr/bin/sleep","3"],capture_output=True,text=True,timeout=5);out["start_exit"]=r.returncode\n q=subprocess.run(["/usr/bin/systemctl","--user","show",unit,"-p","MainPID","-p","ActiveState"],capture_output=True,text=True,timeout=5);props=dict(x.split("=",1) for x in q.stdout.splitlines() if "=" in x)\n try:\n  pid=int(props.get("MainPID","0"));assert pid>1;proc=pathlib.Path("/proc")/str(pid);assert (proc/"comm").read_text().strip()=="sleep";before=(proc/"stat").read_text().rsplit(")",1)[1].split()[19];info={}\n  for line in (proc/"cgroup").read_text().splitlines():\n   parts=line.split(":",2)\n   if len(parts)!=3:continue\n   for n in ("memory","cpu"):\n    if n in parts[1].split(","):\n     root=(pathlib.Path("/sys/fs/cgroup")/n).resolve();cur=(root/parts[2].lstrip("/")).resolve();assert cur==root or root in cur.parents\n     names=("memory.limit_in_bytes",) if n=="memory" else ("cpu.cfs_quota_us","cpu.cfs_period_us");rows=[]\n     for depth in range(20):\n      rows.append({x:(cur/x).read_text().strip() for x in names})\n      if cur==root:break\n      cur=cur.parent\n     else:raise ValueError("depth")\n     info[n]=rows\n  after=(proc/"stat").read_text().rsplit(")",1)[1].split()[19];out["identity_stable"]=before==after;out["ancestor_limits_leaf_first"]=info\n except (OSError,ValueError,AssertionError,IndexError) as e:out["inspection_error"]=type(e).__name__\n if r.returncode==0:time.sleep(4)\nfinally:\n z=subprocess.run(["/usr/bin/systemctl","--user","show",unit,"-p","LoadState","-p","ActiveState"],capture_output=True,text=True,timeout=5);out["after"]=z.stdout.strip().splitlines()\n if "ActiveState=active" in out["after"] or "ActiveState=activating" in out["after"]:\n  c=subprocess.run(["/usr/bin/systemctl","--user","stop",unit],capture_output=True,text=True,timeout=5);out["cleanup_stop_exit"]=c.returncode\n  z=subprocess.run(["/usr/bin/systemctl","--user","show",unit,"-p","LoadState","-p","ActiveState"],capture_output=True,text=True,timeout=5);out["after_cleanup"]=z.stdout.strip().splitlines()\nprint(json.dumps(out))\''
 cmd=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias=xavier','-o','UserKnownHostsFile='+f,'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+p['TailscaleIPs'][0],'xavier@xavier',remote]
 try:
  r=subprocess.run(cmd,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=12)
  t=r.stderr.lower();status='success' if r.returncode==0 else 'host-key-mismatch' if 'host key verification failed' in t or 'remote host identification has changed' in t else 'authentication-denied' if 'permission denied' in t else 'unavailable'
  print('status',status,'exit',r.returncode)
  if r.returncode==0:print(r.stdout.strip())
 except subprocess.TimeoutExpired:print('status timeout')
