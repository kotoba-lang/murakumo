import json, os, signal, subprocess, time
cli='/tmp/codex-amu-1762/bin/amu'
probe='/private/tmp/awai152-macos.Tri1QNza/probe.kotoba'
parent=subprocess.Popen([cli,'run',probe],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
child=None
seen=set()
try:
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  if parent.poll() is not None: raise RuntimeError('CLI exited before guest: '+repr(seen)+' '+repr(parent.stderr.read().decode()))
  listing=subprocess.run(['/bin/ps','-axo','pid=,ppid=,command='],capture_output=True,text=True,check=True).stdout
  for row in listing.splitlines():
   fields=row.strip().split(None,2)
   if len(fields)==3 and int(fields[1])==parent.pid: seen.add(fields[2])
   if len(fields)==3 and int(fields[1])==parent.pid and 'amu-program' in fields[2]:
    child=int(fields[0]);break
  if child: break
  time.sleep(.05)
 if child is None: raise RuntimeError('guest not observed')
 parent.kill(); parent.wait(timeout=3);time.sleep(.2)
 p=subprocess.run(['/bin/ps','-p',str(child),'-o','stat=,command='],capture_output=True,text=True)
 alive=(p.returncode==0 and bool(p.stdout.strip()) and not p.stdout.strip().startswith('Z') and 'amu-program' in p.stdout)
 print(json.dumps({'cliExit':parent.returncode,'guestAliveAfterCliSigkill':alive,'sampleDelaySeconds':.2}),flush=True)
 if not alive: raise RuntimeError('guest did not remain alive')
finally:
 if parent.poll() is None: parent.kill();parent.wait(timeout=3)
 if child:
  try: os.kill(child,signal.SIGKILL)
  except ProcessLookupError: pass
