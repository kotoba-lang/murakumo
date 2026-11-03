# Test only: emitted production argv with endpoint and trust adapted to a local peer.
import json,os,pathlib,shlex,subprocess,tempfile,uuid
p=pathlib.Path('/private/tmp/awai156-https-compat');fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
try:
 token=uuid.uuid4().hex; root='/tmp/awai156-https-'+token; unit='awai156-https-'+token+'.service'
 r=subprocess.run([str(p/'fixture.macho'),unit,root+'/request',root+'/response'],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=8);assert r.returncode==0
 argv=r.stdout.rstrip('\n').split('ARG_SEP');assert argv[-1]=='https://api.murakumo.cloud/v1/chat/completions' and '--fail' in argv and '--fail-with-body' not in argv
finally:os.close(fd)
remote=r'''import json,pathlib,socket,ssl,subprocess,tempfile,threading,time,os,shutil
root=pathlib.Path(ROOT);root.mkdir(mode=0o700);(root/'request').write_text('{"test":true}');cert=root/'cert';key=root/'key';out={};g=subprocess.run(['/usr/bin/openssl','req','-x509','-newkey','ec','-pkeyopt','ec_paramgen_curve:P-256','-nodes','-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost','-keyout',str(key),'-out',str(cert)],capture_output=True,timeout=8);assert g.returncode==0
ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);ctx.load_cert_chain(str(cert),str(key))
def state(unit):
 r=subprocess.run(['/usr/bin/systemctl','--user','show',unit,'-p','LoadState','-p','ActiveState','-p','SubState','-p','Result','-p','ExecMainStatus'],capture_output=True,text=True,timeout=4);return dict(x.split('=',1) for x in r.stdout.splitlines() if '=' in x)
try:
 for status in (200,500):
  unit=UNIT.replace('.service','-'+str(status)+'.service');resp=root/('response-'+str(status));server=socket.socket();server.settimeout(8);server.bind(('127.0.0.1',0));server.listen(1);port=server.getsockname()[1];peer={}
  def serve():
   try:
    conn,_=server.accept();conn=ctx.wrap_socket(conn,server_side=True);conn.settimeout(4);data=b''
    while b'\r\n\r\n' not in data and len(data)<8192:
     z=conn.recv(4096)
     if not z:break
     data+=z
    headers,body=data.split(b'\r\n\r\n',1);length=0
    for h in headers.split(b'\r\n'):
     if h.lower().startswith(b'content-length:'):length=int(h.split(b':',1)[1])
    while len(body)<length and len(body)<8192:body+=conn.recv(4096)
    peer['request_matches']=headers.startswith(b'POST /test HTTP/') and body==b'{"test":true}'
    payload=b'{"ok":true}' if status==200 else b'{"error":"synthetic"}'
    conn.sendall(b'HTTP/1.1 '+str(status).encode()+b' '+(b'OK' if status==200 else b'Internal Server Error')+b'\r\nContent-Length: '+str(len(payload)).encode()+b'\r\nConnection: close\r\n\r\n'+payload);conn.close()
   except Exception as e:peer['error']=type(e).__name__
  t=threading.Thread(target=serve,daemon=True);t.start()
  a=list(ARGV);a[a.index('--unit='+UNIT)]='--unit='+unit;a[a.index(ROOT+'/response')]=str(resp);a[-1]='https://localhost:'+str(port)+'/test';idx=a.index('--disable')+1;a[idx:idx]=['--noproxy','*','--cacert',str(cert)]
  result={}
  try:
   start=subprocess.run(a,capture_output=True,text=True,timeout=6);result['start_exit']=start.returncode;deadline=time.monotonic()+7
   while time.monotonic()<deadline:
    st=state(unit)
    if st.get('SubState') in ('exited','failed') or st.get('LoadState')=='not-found':break
    time.sleep(.1)
   result['state']={k:st.get(k) for k in ('LoadState','SubState','Result','ExecMainStatus')};result['response']=(resp.read_text() if resp.exists() else None)
  finally:
   if state(unit).get('LoadState')!='not-found':
    subprocess.run(['/usr/bin/systemctl','--user','stop',unit],capture_output=True,timeout=5)
    subprocess.run(['/usr/bin/systemctl','--user','reset-failed',unit],capture_output=True,timeout=5)
   st=state(unit);result['after']={k:st.get(k) for k in ('LoadState','ActiveState')};t.join(timeout=9);server.close();result['peer']=peer;result['peer_alive']=t.is_alive()
  out[str(status)]=result
finally:
 shutil.rmtree(root);out['task_dir_removed']=not root.exists()
print(json.dumps(out))'''
remote='ROOT='+repr(root)+'\nUNIT='+repr(unit)+'\nARGV='+repr(argv)+'\n'+remote
s=json.loads(subprocess.check_output(['tailscale','status','--json'],timeout=8));peer=next(x for x in s['Peer'].values() if x.get('HostName')=='xavier');assert peer.get('Online') and peer.get('sshHostKeys')
with tempfile.TemporaryDirectory(prefix='awai156-hostkeys-') as d:
 k=pathlib.Path(d)/'known';k.write_text(''.join('xavier '+x+'\n' for x in peer['sshHostKeys']));os.chmod(k,0o600)
 cmd=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias=xavier','-o','UserKnownHostsFile='+str(k),'-o','GlobalKnownHostsFile=/dev/null','-o','HostName='+peer['TailscaleIPs'][0],'xavier@xavier','/usr/bin/python3 -c '+shlex.quote(remote)]
 r=subprocess.run(cmd,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=35);print('ssh_exit',r.returncode,'stdout',r.stdout,'stderr_nonempty',bool(r.stderr));assert r.returncode==0
