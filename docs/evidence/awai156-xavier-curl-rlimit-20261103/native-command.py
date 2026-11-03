# Test-only composition: native emitter and an isolated synthetic local peer.
import json,os,pathlib,shlex,subprocess,tempfile,uuid
p=pathlib.Path('/private/tmp/awai156-submit-emitter.M4PydO0v'); token=uuid.uuid4().hex; root='/tmp/awai156-native-'+token;unit='awai156-native-'+token+'.service'; request=root+'/request';response=root+'/response';sock=root+'/socket';auth=root+'/auth'
fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
try:
 r=subprocess.run([str(p/'fixture.macho'),unit,request,response,'4',sock,auth],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=8);assert r.returncode==0
 argv=r.stdout.rstrip('\n').split('ARG_SEP');assert argv[0]=='/usr/bin/systemd-run' and '--property=LimitAS=128M' in argv and '--no-block' in argv
finally:os.close(fd)
remote=r'''import json,pathlib,socket,subprocess,threading,time,shutil,os
root=pathlib.Path(ROOT);root.mkdir(mode=0o700);(root/'request').write_text('{"test":true}');(root/'auth').write_text('Authorization: Bearer synthetic-test\n');os.chmod(root/'auth',0o600);expected=b'{"ok":true}';out={};peer={};server=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);server.settimeout(8);server.bind(str(root/'socket'));server.listen(1)
def serve():
 try:
  c,_=server.accept();c.settimeout(4);data=b''
  while b'\r\n\r\n' not in data and len(data)<8192:
   chunk=c.recv(1024)
   if not chunk:break
   data+=chunk
  headers,body=data.split(b'\r\n\r\n',1);size=0
  for line in headers.split(b'\r\n'):
   if line.lower().startswith(b'content-length:'):size=int(line.split(b':',1)[1].strip())
  while len(body)<size and len(body)<8192:
   chunk=c.recv(1024)
   if not chunk:break
   body+=chunk
  peer['body_matches']=body==b'{"test":true}';peer['auth_matches']=b'Authorization: Bearer synthetic-test' in headers
  c.sendall(b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: '+str(len(expected)).encode()+b'\r\nConnection: close\r\n\r\n'+expected);c.close()
 except Exception as e:peer['error']=type(e).__name__
t=threading.Thread(target=serve,daemon=True);t.start()
def state():
 r=subprocess.run(['/usr/bin/systemctl','--user','show',UNIT,'-p','LoadState','-p','ActiveState','-p','SubState','-p','Result','-p','MemoryMax','-p','CPUQuotaPerSecUSec','-p','LimitAS'],capture_output=True,text=True,timeout=4)
 return dict(line.split('=',1) for line in r.stdout.splitlines() if '=' in line)
try:
 start=subprocess.run(ARGV,capture_output=True,text=True,timeout=6);out['start_exit']=start.returncode;out['start_stderr_nonempty']=bool(start.stderr)
 end=time.monotonic()+7;st={}
 while time.monotonic()<end:
  st=state()
  if st.get('SubState') in ('exited','failed') or st.get('LoadState')=='not-found':break
  time.sleep(.1)
 out['state']=st;out['response_matches']=(root/'response').exists() and (root/'response').read_bytes()==expected
finally:
 st=state()
 if st.get('LoadState')!='not-found':
  stop=subprocess.run(['/usr/bin/systemctl','--user','stop',UNIT],capture_output=True,text=True,timeout=5);out['cleanup_stop_exit']=stop.returncode
 final=state();out['after']={k:final.get(k) for k in ('LoadState','ActiveState')}
 t.join(timeout=9);server.close();out['peer']=peer;out['peer_thread_alive']=t.is_alive()
 if final.get('LoadState')=='not-found' and not t.is_alive():shutil.rmtree(root);out['task_dir_removed']=not root.exists()
 else:out['task_dir_removed']=False
print(json.dumps(out))'''
remote='ROOT='+repr(root)+'\nUNIT='+repr(unit)+'\nARGV='+repr(argv)+'\n'+remote
status=json.loads(subprocess.check_output(['tailscale','status','--json'],timeout=8));peer=next(x for x in status['Peer'].values() if x.get('HostName')=='xavier');keys=peer.get('sshHostKeys') or [];assert peer.get('Online') and keys
with tempfile.TemporaryDirectory(prefix='awai156-known-') as d:
 known=pathlib.Path(d)/'known_hosts';known.write_text(''.join('xavier '+x+'\n' for x in keys));os.chmod(known,0o600)
 ssh=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias=xavier','-o','UserKnownHostsFile='+str(known),'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+peer['TailscaleIPs'][0],'xavier@xavier','/usr/bin/python3 -c '+shlex.quote(remote)]
 r=subprocess.run(ssh,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=28);print('ssh_exit',r.returncode);print(r.stdout.strip());print('stderr_nonempty',bool(r.stderr))
