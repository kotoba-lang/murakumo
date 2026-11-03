import os,pathlib,select,subprocess
p=pathlib.Path('/private/tmp/awai151-fifo-sync.cYTNgMRu');fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
A='a'*64;B='b'*64;old='RGQ1\n'+A+'\n';new=old+B+'\n';journal='RGI1\n'+old+'NEXT\n'+new
def run(bin,d,op):
 r=subprocess.run([str(p/(bin+'.macho')),'/'+d.name,op],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5);return r.returncode,r.stdout.strip()
try:
 for phase in ('queue-fail','intent-fail','root-fail'):
  d=p/('fault-'+phase);d.mkdir();(d/'queue.lock').touch();(d/'queue').write_text(old);(d/'queue.intent').write_text(journal)
  result=run(phase,d,'recover');print(phase,result,flush=True);assert result==(1,'fifo/recovery-unknown');assert (d/'queue').read_text()==old and (d/'queue.intent').read_text()==journal and not (d/'queue.last').exists()
  assert run('fifo',d,'front')==(1,'fifo/pending-unknown');assert run('fifo',d,'recover')==(0,'fifo/aborted')
 for state in ('old','new'):
  for phase in ('before-archive','after-archive'):
   d=p/(state+'-'+phase);d.mkdir();(d/'queue.lock').touch();(d/'queue').write_text(old if state=='old' else new);(d/'queue.intent').write_text(journal)
   c=subprocess.Popen([str(p/(phase+'.macho')),'/'+d.name,'recover'],cwd=p,pass_fds=(fd,),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
   try:
    ready,_,_=select.select([c.stdout],[],[],5);assert ready and os.read(c.stdout.fileno(),1)==b'\0' and c.poll() is None;c.kill();c.wait(timeout=3);assert c.returncode==-9
   finally:
    if c.poll() is None:c.kill();c.wait(timeout=3)
   result=run('fifo',d,'recover');expected=(0,'fifo/aborted' if state=='old' else 'fifo/recovered') if phase=='before-archive' else (1,'fifo/no-intent')
   print(state,phase,result,flush=True);assert result==expected;assert (d/'queue').read_text()==(old if state=='old' else new);assert (d/'queue.last').read_text()==journal
 print('fault and interruption checks passed')
finally:os.close(fd)
