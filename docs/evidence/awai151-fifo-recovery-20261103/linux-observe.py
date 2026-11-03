# Linux-only isolated observer; kills only its own synthetic guest.
import json,os,pathlib,select,subprocess
p=pathlib.Path('/private/tmp/awai151-fifo-recovery-linux-recheck');ticket='a'*64
E=dict(os.environ,KEXE_ALLOW='7,35,37,38,39',KEXE_CAP_RESOURCES_35=str(p)); rows=[]
def run(root,op):
 r=subprocess.run([str(p/'fifo.elf'),str(root),op],env=E,capture_output=True,text=True,timeout=5);return r.returncode,r.stdout.strip()
def init(name):
 root=p/name;root.mkdir();(root/'queue').write_text('RGQ1\n');(root/'queue.lock').write_text('');return root
for phase in ('after-intent','after-pending','after-rename','after-sync','after-archive'):
 root=init('kill-'+phase)
 c=subprocess.Popen([str(p/(phase+'.elf')),str(root),'enqueue',ticket],env=E,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 try:
  ready,_,_=select.select([c.stderr],[],[],5);assert ready and c.stderr.readline()==b'PAUSED\n' and c.poll() is None
  c.kill();c.wait(timeout=3); assert c.returncode==-9
  front=run(root,'front');rec=run(root,'recover')
  if phase in ('after-intent','after-pending'):assert front==(1,'fifo/pending-unknown') and rec==(1,'fifo/recovery-unresolved') and (root/'queue.intent').exists()
  elif phase in ('after-rename','after-sync'):assert front==(1,'fifo/pending-unknown') and rec==(0,'fifo/recovered') and run(root,'front')==(0,'fifo/front '+ticket) and run(root,'recover')==(1,'fifo/no-intent')
  else:assert front==(0,'fifo/front '+ticket) and rec==(1,'fifo/no-intent')
  rows.append({'phase':phase,'front':front,'recover':rec})
 finally:
  if c.poll() is None:c.kill();c.wait(timeout=3)
old='RGQ1\n';new=old+ticket+'\n'
for name,journal,state,pending,expected in [('bad-magic','bad',new,False,'fifo/journal-invalid'),('bad-transition','RGI1\n'+old+'NEXT\n'+old,old,False,'fifo/journal-invalid'),('state-mismatch','RGI1\n'+old+'NEXT\n'+new,old,False,'fifo/recovery-unresolved'),('pending-present','RGI1\n'+old+'NEXT\n'+new,new,True,'fifo/recovery-unresolved')]:
 root=init(name);(root/'queue').write_text(state);(root/'queue.intent').write_text(journal)
 if pending:(root/'queue.pending').write_text(new)
 result=run(root,'recover');assert result==(1,expected),(name,result)
 assert (root/'queue.intent').read_text()==journal and (root/'queue').read_text()==state
 rows.append({'case':name,'recover':result})
print(json.dumps(rows),flush=True)
