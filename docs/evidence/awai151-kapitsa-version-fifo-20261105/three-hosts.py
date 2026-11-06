import hashlib,io,json,pathlib,secrets,shlex,subprocess,tarfile,tempfile
repo=pathlib.Path('/Users/junkawasaki/github/workspaces/codex/awai152-darwin-spawn-attribute');watch=pathlib.Path('/private/tmp/awai152-wp04-watch.macho');helper=repo/'scripts/seed/check-macos-watch-helper.py';template=pathlib.Path('/private/tmp/awai151-kapitsa-version-fifo.py').read_text();pins={'naphtali':'a7911905ee6a258bf69e07eaf47ad0d02ecc61996ef50822a41d7174d05f9002','benjamin':'0069b7c8ae635a08faf62f2dbb6909bbf985918275e764d06a22b68b1e15cf0b','judah':'51b6e95ddda282928476947943a9ce71008db54fb6d657edfbf88d182f0310e4'}
remote=r'''import hashlib,io,pathlib,platform,subprocess,sys,tarfile,tempfile
expected=EXPECTED;nonce=sys.argv[1]
with tempfile.TemporaryDirectory(prefix='awai152-kapitsa-native-',dir='/private/tmp') as d:
 root=pathlib.Path(d)
 with tarfile.open(fileobj=io.BytesIO(sys.stdin.buffer.read()),mode='r:') as t:
  for name,digest in expected.items():
   b=t.extractfile(name).read();assert hashlib.sha256(b).hexdigest()==digest
   p=root/name;p.write_bytes(b);p.chmod(0o700 if name.endswith('.macho') else 0o600)
 r=subprocess.run(['/usr/bin/python3',str(root/'probe.py'),'--fifo',str(root/'fifo.macho'),'--fifo-sha256',expected['fifo.macho'],'--watcher',str(root/'watch.macho'),'--watcher-sha256',expected['watch.macho'],'--launcher',str(root/'launcher.macho'),'--launcher-sha256',expected['launcher.macho']],cwd=root,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=18)
 print('PROBE',r.returncode,flush=True);print(r.stdout,end='',flush=True)
 if r.returncode or r.stderr:raise RuntimeError(r.stderr[-900:])
 print('HOST_OS',platform.mac_ver()[0],platform.machine(),subprocess.check_output(['/usr/bin/sw_vers','-buildVersion'],text=True).strip(),flush=True)
print('COMPLETE',nonce,'temp_absent',not root.exists(),flush=True)
'''
status=json.loads(subprocess.check_output(['tailscale','status','--json'],timeout=8));peers={p.get('HostName'):p for p in status['Peer'].values()}
for host,pin in pins.items():
 p=peers[host]
 if not p.get('Online') or not p.get('TailscaleIPs') or not p.get('sshHostKeys'):raise RuntimeError(host+' identity unavailable')
 keys=[k for k in p['sshHostKeys'] if k.startswith(('ssh-ed25519 ','ssh-rsa ','ecdsa-sha2-nistp256 ')) and '\n' not in k]
 if not keys:raise RuntimeError(host+' no host keys')
 data={'launcher.macho':pathlib.Path('/private/tmp/awai152-kapitsa-version-'+host+'.macho').read_bytes(),'watch.macho':watch.read_bytes(),'probe.py':template.encode(),'fifo.macho':pathlib.Path('/private/tmp/awai151-current-fifo.macho').read_bytes(),'check-macos-watch-helper.py':helper.read_bytes()};hashes={k:hashlib.sha256(v).hexdigest() for k,v in data.items()}
 if hashes['fifo.macho']!='e0686d9f82fc3eb5f218a0e716d373ef3a0a9565b0e09f39bef4ce48e0ae9da7' or hashes['launcher.macho']!=pin or hashes['watch.macho']!='ad32993a07f5e8b3db5d46f695c9d1a2b319974f145f13ebe3406c23810eba6e':raise RuntimeError('input pin')
 buf=io.BytesIO()
 with tarfile.open(fileobj=buf,mode='w') as t:
  for name,v in data.items():
   e=tarfile.TarInfo(name);e.size=len(v);e.mode=0o700 if name.endswith('.macho') else 0o600;t.addfile(e,io.BytesIO(v))
 nonce=secrets.token_hex(16)
 with tempfile.TemporaryDirectory(prefix='awai152-hostkeys-') as d:
  known=pathlib.Path(d)/'known';known.write_text(''.join(host+' '+k+'\n' for k in keys));known.chmod(0o600)
  cmd=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=8','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias='+host,'-o','UserKnownHostsFile='+str(known),'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+p['TailscaleIPs'][0],host+'@'+host,'/usr/bin/python3 -c '+shlex.quote(remote.replace('EXPECTED',repr(hashes),1))+' '+shlex.quote(nonce)]
  r=subprocess.run(cmd,input=buf.getvalue(),capture_output=True,timeout=35);out=r.stdout.decode(errors='replace');err=r.stderr.decode(errors='replace');print('HOST',host,'SSH_EXIT',r.returncode,flush=True);print(out,end='',flush=True)
  if r.returncode or ('COMPLETE '+nonce+' temp_absent True') not in out:raise RuntimeError('unknown host outcome '+err[-1000:])
