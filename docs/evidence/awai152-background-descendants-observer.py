import json,pathlib,shlex,subprocess,uuid
peer=next(p for p in json.loads(subprocess.check_output(['tailscale','status','--json']))['Peer'].values() if p.get('HostName')=='benjamin')
ip=peer['TailscaleIPs'][0]; tag=uuid.uuid4().hex; root='/private/tmp/codex-awai152-descendants-'+tag; label='com.codex.awai152.descendants.'+tag
ssh=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes','-o','HostName='+ip,'benjamin']
remote=r'''import json,os,pathlib,plistlib,shutil,subprocess,time
root=pathlib.Path(ROOT); label=LABEL
os.mkdir(root,0o700)
child=''' + repr('''import json,os,pathlib,sys,time\nroot=pathlib.Path(sys.argv[1]); escape=sys.argv[2]=='escape'\npid=os.fork()\nif pid==0:\n if escape: os.setsid()\n (root/'child.json').write_text(json.dumps({'pid':os.getpid(),'pgid':os.getpgrp(),'session':os.getsid(0)}))\n time.sleep(8)\n os._exit(0)\nfor _ in range(100):\n if (root/'child.json').exists(): break\n time.sleep(.02)\n(root/'parent-done').write_text('done')\nos._exit(0)\n''') + r'''
(root/'child.py').write_text(child)
try:
 for mode in ('group','escape'):
  (root/'child.json').unlink(missing_ok=True);(root/'parent-done').unlink(missing_ok=True)
  job=label+'.'+mode;pl=root/(mode+'.plist')
  with pl.open('xb') as f:plistlib.dump({'Label':job,'ProgramArguments':['/usr/bin/python3',str(root/'child.py'),str(root),mode],'RunAtLoad':True,'LaunchOnlyOnce':True,'LimitLoadToSessionType':'Background','ProcessType':'Background','HardResourceLimits':{'CPU':3},'StandardOutPath':str(root/(mode+'.out')),'StandardErrorPath':str(root/(mode+'.err'))},f)
  q=subprocess.run(['/bin/launchctl','load','-S','Background',str(pl)],capture_output=True,text=True,timeout=8)
  if q.returncode: raise RuntimeError('load '+mode+' '+q.stderr[:200])
  try:
   for _ in range(60):
    if (root/'parent-done').exists():break
    time.sleep(.1)
   if not (root/'parent-done').exists():raise RuntimeError('no parent marker: '+(root/(mode+'.err')).read_text()[:300])
   info=json.loads((root/'child.json').read_text());time.sleep(1)
   ps=subprocess.run(['/bin/ps','-p',str(info['pid']),'-o','stat=','-o','command='],capture_output=True,text=True,timeout=4)
   print(json.dumps({'mode':mode,'child':info,'ps_exit':ps.returncode,'ps':ps.stdout.strip(),'job_present':subprocess.run(['/bin/launchctl','list',job],capture_output=True,timeout=4).returncode==0}),flush=True)
   # The child is bounded to eight seconds; do not signal any PID.
   time.sleep(8)
  finally:
   u=subprocess.run(['/bin/launchctl','unload','-S','Background',str(pl)],capture_output=True,text=True,timeout=8)
   absent=subprocess.run(['/bin/launchctl','list',job],capture_output=True,timeout=4).returncode!=0
   print(json.dumps({'cleanup':mode,'unload_exit':u.returncode,'label_absent':absent}),flush=True)
   if not absent: raise RuntimeError('task label remains '+job)
finally:
 # Only remove this run's directory after both exact labels are absent.
 absent=all(subprocess.run(['/bin/launchctl','list',label+'.'+m],capture_output=True,timeout=4).returncode!=0 for m in ('group','escape'))
 if absent:shutil.rmtree(root)
 print(json.dumps({'all_labels_absent':absent,'temp_removed':not root.exists(),'root':str(root)}),flush=True)
'''
code='ROOT='+repr(root)+'\nLABEL='+repr(label)+'\n'+remote
try:
 p=subprocess.run(ssh+['python3 -c '+shlex.quote(code)],capture_output=True,text=True,timeout=55)
 print(json.dumps({'exit':p.returncode,'out':p.stdout,'err':p.stderr[-1000:],'label':label,'root':root}),flush=True)
except subprocess.TimeoutExpired:
 print(json.dumps({'timeout':True,'label':label,'root':root}),flush=True)
