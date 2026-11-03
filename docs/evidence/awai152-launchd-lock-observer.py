# One-off launchd lock/escape observation; not a governor implementation.
import fcntl,json,os,pathlib,plistlib,signal,subprocess,sys,time,uuid
base=pathlib.Path('/private/tmp/awai152-launchd-lock.FfvLoJ6k');domain='gui/%d'%os.getuid();worker=str(base/'worker.py')
def process(pid):
 r=subprocess.run(['/bin/ps','-p',str(pid),'-o','stat=,pgid=,command='],capture_output=True,text=True)
 return r.stdout.strip() if r.returncode==0 else ''
def run(escape):
 label='com.awai.codex.locktest.'+uuid.uuid4().hex;target=domain+'/'+label;root=base/label;root.mkdir(mode=0o700);path=root/'job.plist';loaded=False;parent=child=group=None
 cfg={'Label':label,'ProgramArguments':[sys.executable,worker,str(root),'1' if escape else '0'],'RunAtLoad':True,'KeepAlive':False,'LaunchOnlyOnce':True,'AbandonProcessGroup':False,'StandardOutPath':str(root/'out'),'StandardErrorPath':str(root/'err')};path.write_bytes(plistlib.dumps(cfg))
 try:
  subprocess.run(['/bin/launchctl','bootstrap',domain,str(path)],capture_output=True,check=True);loaded=True
  deadline=time.monotonic()+8;data=None
  while time.monotonic()<deadline:
   try:data=json.loads((root/'ready.json').read_text());break
   except (OSError,ValueError):time.sleep(.03)
  if not data:raise RuntimeError('child readiness unavailable')
  parent,child,group=data['parent'],data['child'],data['group'];p=process(parent);c=process(child)
  if worker not in p or worker not in c:raise RuntimeError('fixture process identity mismatch')
  parentgroup=int(p.split(None,2)[1]);same=parentgroup==group
  if same==escape:raise RuntimeError('unexpected test process group')
  os.kill(parent,signal.SIGKILL);parent=None;time.sleep(.35)
  c=process(child);alive=bool(c and worker in c and not c.startswith('Z'))
  fd=os.open(root/'slot.lock',os.O_RDWR)
  try:
   try:fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB);reacquired=True
   except BlockingIOError:reacquired=False
  finally:os.close(fd)
  result={'escapedGroup':escape,'childAliveAfterParentDeath':alive,'lockReacquired':reacquired,'sampleDelaySeconds':.35}
  print(json.dumps(result),flush=True)
  if alive!=escape or not reacquired:raise RuntimeError('unexpected result')
 finally:
  for pid in [parent,child]:
   if pid:
    s=process(pid)
    if s and worker in s:
     try:os.kill(pid,signal.SIGKILL)
     except ProcessLookupError:pass
  if loaded:
   subprocess.run(['/bin/launchctl','bootout',target],capture_output=True)
   if subprocess.run(['/bin/launchctl','print',target],capture_output=True).returncode==0:raise RuntimeError('job remains: '+target)
run(False)
run(True)
