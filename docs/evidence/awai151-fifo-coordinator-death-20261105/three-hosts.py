import hashlib,io,json,pathlib,secrets,shlex,subprocess,tarfile,tempfile
items={'fifo.macho':pathlib.Path('/private/tmp/awai151-current-fifo.macho'),'watch.macho':pathlib.Path('/private/tmp/awai152-wp04-watch.macho'),'probe.py':pathlib.Path('/Users/junkawasaki/github/workspaces/codex/awai151-bounded-fifo/scripts/native/check-fifo-coordinator-death.py')};data={k:v.read_bytes() for k,v in items.items()};hashes={k:hashlib.sha256(v).hexdigest() for k,v in data.items()}
assert hashes['fifo.macho']=='e0686d9f82fc3eb5f218a0e716d373ef3a0a9565b0e09f39bef4ce48e0ae9da7' and hashes['watch.macho']=='ad32993a07f5e8b3db5d46f695c9d1a2b319974f145f13ebe3406c23810eba6e'
b=io.BytesIO()
with tarfile.open(fileobj=b,mode='w') as t:
 for name,v in data.items():
  e=tarfile.TarInfo(name);e.size=len(v);e.mode=0o700 if name.endswith('.macho') else 0o600;t.addfile(e,io.BytesIO(v))
remote=r'''import hashlib,io,pathlib,platform,subprocess,sys,tarfile,tempfile
expected=EXPECTED;nonce=sys.argv[1]
with tempfile.TemporaryDirectory(prefix='awai151-coord-fleet-',dir='/private/tmp') as d:
 root=pathlib.Path(d)
 with tarfile.open(fileobj=io.BytesIO(sys.stdin.buffer.read()),mode='r:') as t:
  for name,digest in expected.items():
   data=t.extractfile(name).read();assert hashlib.sha256(data).hexdigest()==digest
   p=root/name;p.write_bytes(data);p.chmod(0o700 if name.endswith('.macho') else 0o600)
 cmd=['/usr/bin/python3',str(root/'probe.py'),'--fifo',str(root/'fifo.macho'),'--fifo-sha256',expected['fifo.macho'],'--watcher',str(root/'watch.macho'),'--watcher-sha256',expected['watch.macho']]
 p=subprocess.run(cmd,capture_output=True,text=True,timeout=25);print('PROBE',p.returncode,flush=True);print(p.stdout,end='',flush=True)
 if p.returncode or p.stderr:raise RuntimeError(p.stderr[-1000:])
 print('HOST_OS',platform.mac_ver()[0],platform.machine(),subprocess.check_output(['/usr/bin/sw_vers','-buildVersion'],text=True).strip(),flush=True)
print('COMPLETE',nonce,'temp_absent',not root.exists(),flush=True)
'''.replace('EXPECTED',repr(hashes),1)
status=json.loads(subprocess.check_output(['tailscale','status','--json'],timeout=8));peers={p.get('HostName'):p for p in status['Peer'].values()}
for host in ('naphtali','benjamin','judah'):
 p=peers[host]
 if not p.get('Online') or not p.get('TailscaleIPs') or not p.get('sshHostKeys'):raise RuntimeError(host+' identity unavailable')
 keys=[k for k in p['sshHostKeys'] if k.startswith(('ssh-ed25519 ','ssh-rsa ','ecdsa-sha2-nistp256 ')) and '\n' not in k]
 if not keys:raise RuntimeError('host key unavailable')
 nonce=secrets.token_hex(16)
 with tempfile.TemporaryDirectory(prefix='awai151-hostkeys-') as d:
  known=pathlib.Path(d)/'known';known.write_text(''.join(host+' '+k+'\n' for k in keys));known.chmod(0o600)
  cmd=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=8','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias='+host,'-o','UserKnownHostsFile='+str(known),'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+p['TailscaleIPs'][0],host+'@'+host,'/usr/bin/python3 -c '+shlex.quote(remote)+' '+shlex.quote(nonce)]
  p=subprocess.run(cmd,input=b.getvalue(),capture_output=True,timeout=35);out=p.stdout.decode(errors='replace');err=p.stderr.decode(errors='replace');print('HOST',host,'SSH_EXIT',p.returncode,flush=True);print(out,end='',flush=True)
  if p.returncode or ('COMPLETE '+nonce+' temp_absent True') not in out:raise RuntimeError('unknown host outcome '+err[-1000:])
