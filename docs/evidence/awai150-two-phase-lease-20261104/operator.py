import hashlib,io,json,pathlib,secrets,shlex,subprocess,tarfile,tempfile
source=pathlib.Path('/private/tmp/awai-flint-two-phase-quorum.kotoba').read_bytes()
compiler=pathlib.Path('/private/tmp/awai1828-bootstrap-41833823850/F3.elf').read_bytes()
assert hashlib.sha256(compiler).hexdigest()=='46fce6f620a73d91c7ec482687856a3354646f416e05085a6fa61022d37a5e0b'
status=json.loads(subprocess.check_output(['tailscale','status','--json'],timeout=8));peer=next(x for x in status['Peer'].values() if x.get('HostName')=='xavier')
if not peer.get('Online') or not peer.get('TailscaleIPs') or not peer.get('sshHostKeys'):raise RuntimeError('host identity unavailable')
buf=io.BytesIO()
with tarfile.open(fileobj=buf,mode='w') as tf:
 for name,data in [('compiler',compiler),('source.kotoba',source)]:
  info=tarfile.TarInfo(name);info.size=len(data);info.mode=0o700 if name=='compiler' else 0o600;tf.addfile(info,io.BytesIO(data))
remote=r'''import hashlib,hmac,io,json,os,pathlib,resource,socket,subprocess,sys,tarfile,tempfile,threading,time
nonce=sys.argv[1];root=pathlib.Path(tempfile.mkdtemp(prefix='awai-lease-loopback-'))
with tarfile.open(fileobj=io.BytesIO(sys.stdin.buffer.read()),mode='r:') as tf:
 for name in ('compiler','source.kotoba'):
  data=tf.extractfile(name).read();p=root/name;p.write_bytes(data);p.chmod(0o700 if name=='compiler' else 0o600)
if hashlib.sha256((root/'compiler').read_bytes()).hexdigest()!='46fce6f620a73d91c7ec482687856a3354646f416e05085a6fa61022d37a5e0b':raise RuntimeError('compiler mismatch')
token='t'*32;(root/'token').write_text(token);(root/'token').chmod(0o600)
def limit():resource.setrlimit(resource.RLIMIT_CPU,(3,4));resource.setrlimit(resource.RLIMIT_AS,(8589934592,8589934592));resource.setrlimit(resource.RLIMIT_CORE,(0,0))
def call(args,env):
 fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY)
 def setup():os.dup2(fd,3);limit()
 proc=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,pass_fds=(fd,),preexec_fn=setup)
 start=time.monotonic();peak=0
 while proc.poll() is None:
  if time.monotonic()-start>12:proc.kill();break
  try:
   for line in pathlib.Path('/proc/'+str(proc.pid)+'/status').read_text().splitlines():
    if line.startswith('VmRSS:'):peak=max(peak,int(line.split()[1]));break
   if peak>204800:proc.kill();break
  except (FileNotFoundError,ProcessLookupError):pass
  time.sleep(.05)
 out,err=proc.communicate();os.close(fd);return proc.returncode,out,err,peak
env=dict(os.environ,KEXE_CAP_RESOURCES_35=str(root),KEXE_CPU_SECONDS='3',KEXE_WALL_SECONDS='10')
rc,out,err,peak=call([str(root/'compiler'),'compile',str(root/'source.kotoba'),'--target','aarch64-linux','--output',str(root/'fixture.elf'),'--format','static'],env)
print('compile',rc,'peak_kib',peak,'source_sha256',hashlib.sha256((root/'source.kotoba').read_bytes()).hexdigest(),flush=True)
if rc:raise RuntimeError('compile failed '+err[-300:].decode(errors='replace'))
binary=root/'fixture.elf';binary.chmod(0o700);print('image_sha256',hashlib.sha256(binary.read_bytes()).hexdigest(),flush=True)
def scenario(mode):
 listeners=[];threads=[];stop=threading.Event();errors=[];stats=[0,0,0]
 try:
  for index in range(3):
   sock=socket.socket();sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);sock.bind(('127.0.0.'+str(index+1),0));sock.listen(8);sock.settimeout(.2);listeners.append(sock)
  ports=','.join('%s:%s'%x.getsockname() for x in listeners)
  def serve(index,sock):
   chosen=None;accepted_t=0;accepted_id='-';prepares=0
   while not stop.is_set():
    try:conn,_=sock.accept()
    except socket.timeout:continue
    except OSError:break
    try:
     conn.settimeout(2);data=b''
     while b'\n' not in data and len(data)<8192:
      chunk=conn.recv(8192)
      if not chunk:break
      data+=chunk
     raw=data.decode().strip();mac,line=raw.split(' ',1)
     expected=hmac.new(token.encode(),('req '+line).encode(),hashlib.sha256).hexdigest()
     if not hmac.compare_digest(mac,expected):raise ValueError('request mac')
     parts=line.split(' ',4);op,epoch,ballot,node=parts[:4];stats[index]+=1
     if op=='prepare':
      prepares+=1;value=chosen or '-'
      if mode=='changed' and prepares>=3 and chosen:
       v=json.loads(chosen);v['node']='node-b';value=json.dumps(v,sort_keys=True,separators=(',',':'))
      reply='ok %s %s %s %s %s'%(ballot,node,accepted_t,accepted_id,value)
     elif op=='accept':
      chosen=parts[4];accepted_t=int(ballot);accepted_id=node
      reply='ok %s %s %s %s %s'%(ballot,node,accepted_t,accepted_id,chosen)
     else:raise ValueError('unexpected operation')
     signed=hmac.new(token.encode(),('rep '+line+'\n'+reply).encode(),hashlib.sha256).hexdigest()
     if mode=='badmac' and prepares>=3 and index>0:signed='0'*64
     conn.sendall((signed+' '+reply+'\n').encode())
    except (ValueError,UnicodeError,OSError) as exc:errors.append(type(exc).__name__)
    finally:conn.close()
  for i,sock in enumerate(listeners):
   thread=threading.Thread(target=serve,args=(i,sock),daemon=True);thread.start();threads.append(thread)
  area=root/mode;area.mkdir()
  e=dict(env,KEXE_ALLOW='7,20,35,37,38,39,45,46,47',KEXE_CAP_RESOURCES_20='/usr/bin/systemctl',KEXE_SPAWN_ENV='DBUS_SESSION_BUS_ADDRESS:XDG_RUNTIME_DIR',KEXE_CAP_RESOURCES_45=ports)
  rc,out,err,peak=call([str(binary),mode,str(area),str(root/'token'),ports],e)
  expected=''
  value=out.decode(errors='replace').strip()
  print('CASE',nonce,mode,'exit',rc,'output',value,'requests',stats,'stderr_bytes',len(err),'peak_kib',peak,'server_errors',errors,flush=True)
  if rc!=0 or value!=expected or err or errors:raise RuntimeError('scenario failed')
 finally:
  stop.set()
  for sock in listeners:sock.close()
  for thread in threads:thread.join(timeout=2)
  if any(t.is_alive() for t in threads):raise RuntimeError('test listener not stopped')
unit='itonami-count-7a655439725342cfa889212c.service'
def show(prop):
 r=subprocess.run(['/usr/bin/systemctl','--user','show','--property='+prop,'--value',unit],capture_output=True,text=True,timeout=5)
 return r.stdout.strip() if r.returncode==0 else '?'
if show('LoadState')!='not-found':raise RuntimeError('test unit already exists')
started=False
try:
 started=True
 r=subprocess.run(['/usr/bin/systemd-run','--user','--quiet','--no-block','--unit='+unit,'--property=Type=oneshot','--property=RemainAfterExit=yes','/usr/bin/true'],capture_output=True,text=True,timeout=8)
 if r.returncode:raise RuntimeError('test unit start unknown '+r.stderr[-200:])
 for _ in range(40):
  if show('SubState')=='exited':break
  time.sleep(.1)
 if show('SubState')!='exited':raise RuntimeError('test unit did not exit')
 for mode in ('same','changed','badmac'):scenario(mode)
finally:
 if started:
  stopped=subprocess.run(['/usr/bin/systemctl','--user','stop',unit],capture_output=True,text=True,timeout=8)
  if stopped.returncode:raise RuntimeError('test unit stop unknown')
  for _ in range(30):
   if show('LoadState')=='not-found':break
   time.sleep(.1)
print('final-unit',show('LoadState'),show('ActiveState'),flush=True)
if show('LoadState')!='not-found' or show('ActiveState')!='inactive':raise RuntimeError('test unit still registered')
print('LEASE_LOOPBACK_COMPLETE',nonce,flush=True);print('artifact_directory',root,flush=True)
'''
nonce=secrets.token_hex(16)
with tempfile.TemporaryDirectory(prefix='awai-loopback-hostkeys-') as d:
 known=pathlib.Path(d)/'known';keys=[x for x in peer['sshHostKeys'] if x.startswith(('ssh-ed25519 ','ssh-rsa ','ecdsa-sha2-nistp256 ')) and '\n' not in x]
 if not keys:raise RuntimeError('host key unavailable')
 known.write_text(''.join('xavier '+k+'\n' for k in keys));known.chmod(0o600)
 cmd=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias=xavier','-o','UserKnownHostsFile='+str(known),'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+peer['TailscaleIPs'][0],'xavier@xavier','/usr/bin/python3 -c '+shlex.quote(remote)+' '+shlex.quote(nonce)]
 result=subprocess.run(cmd,input=buf.getvalue(),capture_output=True,timeout=55)
 out=result.stdout.decode(errors='replace');err=result.stderr.decode(errors='replace');print('ssh_exit',result.returncode);print(out[-3000:]);print('ssh_stderr_tail',err[-500:])
 if 'LEASE_LOOPBACK_COMPLETE '+nonce not in out:raise RuntimeError('missing in-band completion; outcome unknown')
