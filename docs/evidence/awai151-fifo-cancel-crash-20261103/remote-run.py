# Test-only, ephemeral bounded diagnostics on two requested fleet hosts.
import base64,hashlib,json,pathlib,shlex,subprocess,sys,uuid
p=pathlib.Path('/private/tmp/awai151-cancel-crash')
names=['fifo','after-intent','after-pending','after-rename','after-sync','after-archive']
files={n+'.macho':base64.b64encode((p/(n+'.macho')).read_bytes()).decode() for n in names}
hashes={n:hashlib.sha256(base64.b64decode(data)).hexdigest() for n,data in files.items()}
observer=(p/'crash-observer.py').read_text().replace("p=pathlib.Path('/private/tmp/awai151-cancel-crash')","p=pathlib.Path.cwd()")
data=json.dumps({'files':files,'hashes':hashes,'observer':observer}).encode()
remote=r'''import base64,hashlib,json,os,pathlib,shutil,subprocess,sys
p=pathlib.Path(ROOT); payload=json.load(sys.stdin);os.mkdir(p,0o700)
try:
 for name,s in payload['files'].items():
  assert name in ('fifo.macho','after-intent.macho','after-pending.macho','after-rename.macho','after-sync.macho','after-archive.macho')
  b=base64.b64decode(s,validate=True);assert hashlib.sha256(b).hexdigest()==payload['hashes'][name]
  f=p/name;f.write_bytes(b);os.chmod(f,0o700)
  subprocess.run(['/usr/bin/codesign','--verify','--strict',str(f)],check=True,capture_output=True,timeout=8)
 (p/'observer.py').write_text(payload['observer'])
 r=subprocess.run(['/usr/bin/python3','observer.py'],cwd=p,capture_output=True,text=True,timeout=25)
 print(json.dumps({'observer_exit':r.returncode,'out':r.stdout,'err':r.stderr[-500:]}),flush=True)
finally:
 shutil.rmtree(p);print(json.dumps({'removed':not p.exists()}),flush=True)
'''
peers=json.loads(subprocess.check_output(['tailscale','status','--json']))['Peer'].values()
for host in ('benjamin','levi'):
 peer=next(x for x in peers if x.get('HostName')==host);ip=peer['TailscaleIPs'][0];root='/private/tmp/codex-awai151-cancel-'+uuid.uuid4().hex
 ssh=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostName='+ip,host]
 try:
  r=subprocess.run(ssh+['python3 -c '+shlex.quote('ROOT='+repr(root)+'\n'+remote)],input=data,capture_output=True,timeout=50)
  print(json.dumps({'host':host,'ssh_exit':r.returncode,'stdout':r.stdout.decode(errors='replace'),'stderr':r.stderr.decode(errors='replace')[-500:],'root':root}),flush=True)
 except subprocess.TimeoutExpired:
  print(json.dumps({'host':host,'timeout':True,'root':root}),flush=True)
  break
