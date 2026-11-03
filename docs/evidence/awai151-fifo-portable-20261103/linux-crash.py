# Isolated test observer; intentionally kills only its own paused fixture.
import json, os, pathlib, select, subprocess
base=pathlib.Path('/private/tmp/awai151-fifo-portable-final'); ticket='a'*64
env=dict(os.environ,KEXE_ALLOW='7,35,37,38,39',KEXE_CAP_RESOURCES_35=str(base))
for phase in ('after-intent','after-pending','after-rename','after-sync','after-archive'):
 root=base/('fixture-'+phase); root.mkdir(); (root/'queue').write_text('RGQ1\n'); (root/'queue.lock').write_text('')
 p=subprocess.Popen([str(base/(phase+'.elf')),str(root),'enqueue',ticket],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 try:
  ready,_,_=select.select([p.stderr],[],[],5)
  if not ready: raise RuntimeError('fixture did not reach pause '+phase)
  line=p.stderr.readline()
  if line!=b'PAUSED\n': raise RuntimeError('bad pause '+phase+' '+repr(line))
  p.kill(); p.wait(timeout=3)
  r=subprocess.run([str(base/'normal.elf'),str(root),'front'],env=env,capture_output=True,text=True,timeout=5)
  intent=(root/'queue.intent').exists(); pending=(root/'queue.pending').exists(); state=(root/'queue').read_text()
  expect=(0,'fifo/front '+ticket) if phase=='after-archive' else (1,'fifo/pending-unknown')
  assert (r.returncode,r.stdout.strip())==expect,(phase,r.returncode,r.stdout,r.stderr)
  assert intent==(phase!='after-archive'),(phase,intent)
  assert state==('RGQ1\n'+ticket+'\n' if phase in ('after-rename','after-sync','after-archive') else 'RGQ1\n')
  print(json.dumps({'phase':phase,'killedExit':p.returncode,'intent':intent,'pending':pending,'front':r.stdout.strip(),'frontExit':r.returncode}),flush=True)
 finally:
  if p.poll() is None: p.kill(); p.wait(timeout=3)
