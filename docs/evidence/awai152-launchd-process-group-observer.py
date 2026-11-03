# One-time local observation; launchd is not an operational governor here.
import json, os, pathlib, plistlib, re, signal, subprocess, time, uuid
root=pathlib.Path('/private/tmp/awai152-launchd.TeKkxPj4')
cli='/tmp/codex-amu-1762/bin/amu'; source=str(root/'probe.kotoba');domain='gui/%d'%os.getuid()
def ps():
 out=subprocess.run(['/bin/ps','-axo','pid=,ppid=,pgid=,stat=,command='],capture_output=True,text=True,check=True).stdout
 rows=[]
 for line in out.splitlines():
  f=line.strip().split(None,4)
  if len(f)==5:
   try: rows.append((int(f[0]),int(f[1]),int(f[2]),f[3],f[4]))
   except ValueError: pass
 return rows
def experiment(abandon):
 label='com.awai.codex.parentdeath.'+uuid.uuid4().hex
 path=root/(label+'.plist');target=domain+'/'+label;parent=child=group=None;loaded=False
 cfg={'Label':label,'ProgramArguments':[cli,'run',source],'RunAtLoad':True,'KeepAlive':False,'LaunchOnlyOnce':True,'AbandonProcessGroup':abandon,'StandardOutPath':str(root/(label+'.out')),'StandardErrorPath':str(root/(label+'.err'))}
 path.write_bytes(plistlib.dumps(cfg))
 try:
  subprocess.run(['/bin/launchctl','bootstrap',domain,str(path)],check=True,capture_output=True);loaded=True
  deadline=time.monotonic()+8
  while time.monotonic()<deadline:
   rows=ps()
   parents=[x for x in rows if x[4].startswith(cli+' run '+source)]
   for p in parents:
    children=[x for x in rows if x[1]==p[0] and 'amu-program' in x[4]]
    if children: parent,child,group=p[0],children[0][0],p[2]; childgroup=children[0][2];break
   if child:break
   time.sleep(.03)
  if child is None:raise RuntimeError('test guest not observed')
  if group!=childgroup:raise RuntimeError('test guest not in launchd job process group')
  os.kill(parent,signal.SIGKILL);parent=None
  deadline=time.monotonic()+1
  alive=True
  while time.monotonic()<deadline:
   rows=ps();matches=[x for x in rows if x[0]==child and x[2]==group and 'amu-program' in x[4] and not x[3].startswith('Z')]
   alive=bool(matches)
   if not alive:break
   time.sleep(.05)
  result={'abandonProcessGroup':abandon,'sameProcessGroup':True,'guestAliveAfterUpTo1Second':alive}
  print(json.dumps(result),flush=True)
  if alive != abandon:raise RuntimeError('unexpected process-group cleanup result')
 finally:
  if parent is not None:
   try:os.kill(parent,signal.SIGKILL)
   except ProcessLookupError:pass
  if child is not None:
   for x in ps():
    if x[0]==child and x[2]==group and 'amu-program' in x[4]:
     try:os.kill(child,signal.SIGKILL)
     except ProcessLookupError:pass
  if loaded:
   subprocess.run(['/bin/launchctl','bootout',target],capture_output=True)
   check=subprocess.run(['/bin/launchctl','print',target],capture_output=True)
   if check.returncode==0:raise RuntimeError('test launchd job still registered: '+label)
  path.unlink(missing_ok=True)
experiment(False)
experiment(True)
