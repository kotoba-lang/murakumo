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
 remote='/usr/bin/python3 -c \'import os,pathlib,subprocess,json,stat\nuid=os.getuid();out={"controllers":{},"mounts":[]}\nfor line in pathlib.Path("/proc/cgroups").read_text().splitlines():\n if not line or line.startswith("#"):continue\n x=line.split()\n if x[0] in ("cpu","cpuacct","memory","pids"):out["controllers"][x[0]]={"hierarchy":int(x[1]),"enabled":x[3]=="1"}\nfor line in pathlib.Path("/proc/self/mountinfo").read_text().splitlines():\n left,sep,right=line.partition(" - ");l=left.split();r=right.split()\n if sep and r[0] in ("cgroup","cgroup2"):\n  flags=set((l[5]+","+r[2]).split(","));names=sorted(flags.intersection({"cpu","cpuacct","memory","pids","name=systemd"}))\n  if names or r[0]=="cgroup2":out["mounts"].append({"type":r[0],"controller_names":names,"mount_rw":"rw" in l[5].split(","),"mount_ro":"ro" in l[5].split(",")})\nr=subprocess.run(["/usr/bin/systemctl","show","user@"+str(uid)+".service","-p","MainPID","--value"],capture_output=True,text=True,timeout=5)\nif r.returncode==0 and r.stdout.strip().isdigit() and int(r.stdout.strip())>1:\n pid=int(r.stdout.strip());proc=pathlib.Path("/proc")/str(pid);before=(proc/"stat").read_text().rsplit(")",1)[1].split()[19]\n for line in (proc/"cgroup").read_text().splitlines():\n  p=line.split(":",2)\n  if len(p)!=3:continue\n  for n in ("memory","cpu","pids"):\n   if n in p[1].split(","):\n    d=pathlib.Path("/sys/fs/cgroup")/n/p[2].lstrip("/")\n    if d.is_dir():\n     st=d.stat();out["controllers"].setdefault(n,{}).update(owner_is_ssh_user=st.st_uid==uid,mode=oct(stat.S_IMODE(st.st_mode)),writable=os.access(d,os.W_OK))\n out["manager_identity_stable"]=before==(proc/"stat").read_text().rsplit(")",1)[1].split()[19]\nprint(json.dumps(out))\''
 cmd=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias=xavier','-o','UserKnownHostsFile='+f,'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+p['TailscaleIPs'][0],'xavier@xavier',remote]
 try:
  r=subprocess.run(cmd,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=12)
  t=r.stderr.lower();status='success' if r.returncode==0 else 'host-key-mismatch' if 'host key verification failed' in t or 'remote host identification has changed' in t else 'authentication-denied' if 'permission denied' in t else 'unavailable'
  print('status',status,'exit',r.returncode)
  if r.returncode==0:print(r.stdout.strip())
 except subprocess.TimeoutExpired:print('status timeout')
