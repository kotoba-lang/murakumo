# One-off launchd resource-limit observation; does not implement a governor.
import json,os,pathlib,plistlib,subprocess,sys,time,uuid
root=pathlib.Path('/private/tmp/awai151-rss.3ycDKMmL');worker=str(root/'worker.py');domain='gui/%d'%os.getuid()
def run(mode):
 limited=(mode=="plist")
 label='com.awai.codex.rsstest.'+uuid.uuid4().hex;target=domain+'/'+label;d=root/label;d.mkdir(mode=0o700);plist=d/'job.plist';result=d/'result.json';loaded=False
 cfg={'Label':label,'ProgramArguments':[sys.executable,worker,str(result),'explicit' if mode=='explicit' else 'none'],'RunAtLoad':True,'KeepAlive':False,'LaunchOnlyOnce':True,'StandardOutPath':str(d/'out'),'StandardErrorPath':str(d/'err')}
 if limited:cfg.update({'SoftResourceLimits':{'ResidentSetSize':8*1024*1024},'HardResourceLimits':{'ResidentSetSize':8*1024*1024}})
 plist.write_bytes(plistlib.dumps(cfg))
 try:
  subprocess.run(['/bin/launchctl','bootstrap',domain,str(plist)],capture_output=True,check=True);loaded=True
  end=time.monotonic()+8;data=None
  while time.monotonic()<end:
   try:data=json.loads(result.read_text());break
   except (OSError,ValueError):time.sleep(.03)
  if data is None:raise RuntimeError('worker produced no result; '+(d/'err').read_text())
  p=subprocess.run(['/bin/ps','-p',str(data['pid']),'-o','rss=,command='],capture_output=True,text=True,check=True)
  fields=p.stdout.strip().split(None,1)
  if len(fields)!=2 or worker not in fields[1]:raise RuntimeError('worker identity mismatch')
  data['rssKiB']=int(fields[0]);data['mode']=mode
  print(json.dumps(data),flush=True)
  if mode=='explicit' and (data['rssLimit']!=[8388608,8388608] or data['rssKiB']*1024<=8388608):raise RuntimeError('explicit limit not installed or RSS did not exceed it')
 finally:
  if loaded:
   subprocess.run(['/bin/launchctl','bootout',target],capture_output=True)
   if subprocess.run(['/bin/launchctl','print',target],capture_output=True).returncode==0:raise RuntimeError('job still registered')
run('baseline')
run('plist')
run('explicit')
