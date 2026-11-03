import json, os, pathlib, signal, subprocess, time
base = pathlib.Path('/var/home/core/awai152-process')
env = dict(os.environ, KEXE_ALLOW='20,35,37,38', KEXE_CAP_RESOURCES_20='/usr/bin/sleep', KEXE_CAP_RESOURCES_35=str(base))
guest = subprocess.Popen([str(base/'probe.elf')], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
child = None
try:
 deadline=time.monotonic()+4
 while time.monotonic()<deadline:
  for p in pathlib.Path('/proc').iterdir():
   if not p.name.isdigit(): continue
   try:
    args=(p/'cmdline').read_bytes().split(b'\0')
    if args[:2]==[b'/usr/bin/sleep',b'6']:
     child=int(p.name); break
   except (OSError,ProcessLookupError): pass
  if child is not None: break
  if guest.poll() is not None: raise RuntimeError('guest exited before child')
  time.sleep(.05)
 if child is None: raise RuntimeError('sleep child not observed')
 guest.kill(); guest.wait(timeout=3); time.sleep(.5)
 p=pathlib.Path('/proc')/str(child)
 cmd=(p/'cmdline').read_bytes().split(b'\0') if p.exists() else []
 status=(p/'status').read_text() if p.exists() else ''
 alive=cmd[:2]==[b'/usr/bin/sleep',b'6'] and not any(s in status for s in ['State:\tZ','State:\tX'])
 print(json.dumps({'guestExit':guest.returncode,'childAliveAfterGuestSigkill':alive,'sampleDelaySeconds':.5}),flush=True)
 if not alive: raise RuntimeError('child did not remain alive')
finally:
 if guest.poll() is None: guest.kill(); guest.wait(timeout=3)
 if child is not None:
  try: os.kill(child,signal.SIGKILL)
  except ProcessLookupError: pass
