# Test observer only; runs isolated synthetic fixtures via diagnostic FD3.
import concurrent.futures,json,os,pathlib,subprocess
p=pathlib.Path.cwd(); root=p/'case-contract2';root.mkdir();(root/'queue.lock').write_text('');(root/'queue').write_text('RGQ1\n')
fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
A='a'*64; B='b'*64; C='c'*64
rows=[]
def run(name,op,ticket=None):
 args=[str(p/'fifo.macho'),'/case-contract2',op]+([ticket] if ticket else [])
 r=subprocess.run(args,cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=8)
 row={'case':name,'exit':r.returncode,'stdout':r.stdout.strip(),'stderr':r.stderr};rows.append(row);return (r.returncode,r.stdout.strip())
try:
 assert run('empty','front')==(0,'fifo/empty')
 assert run('enqueue-a','enqueue',A)==(0,'fifo/enqueued')
 assert run('enqueue-b','enqueue',B)==(0,'fifo/enqueued')
 assert run('duplicate','enqueue',A)==(1,'fifo/already-queued')
 assert run('first','front')==(0,'fifo/front '+A)
 assert run('not-front','dequeue',B)==(1,'fifo/not-front')
 assert run('dequeue','dequeue',A)==(0,'fifo/dequeued')
 assert run('second','front')==(0,'fifo/front '+B)
 (root/'queue.pending').write_text('partial')
 assert run('pending','enqueue',C)==(1,'fifo/pending-unknown')
 (root/'queue.pending').unlink();(root/'queue.intent').write_text('intent\n')
 assert run('intent','front')==(1,'fifo/pending-unknown')
 (root/'queue.intent').unlink();(root/'queue').write_text('RGQ1\n'+B+'\n'+B+'\n')
 assert run('duplicate-state','front')==(1,'fifo/state-invalid')
 (root/'queue').write_text('RGQ1\n')
 with concurrent.futures.ThreadPoolExecutor(max_workers=8) as e:
  results=list(e.map(lambda i:run('race-'+str(i),'enqueue',format(i,'064x')),range(8)))
 assert all(x[1] in ('fifo/enqueued','fifo/busy') for x in results),results
 accepted=[format(i,'064x') for i,x in enumerate(results) if x[1]=='fifo/enqueued'];lines=(root/'queue').read_text().splitlines()
 assert lines[0]=='RGQ1' and len(lines[1:])==len(accepted) and set(lines[1:])==set(accepted)
 print(json.dumps({'cases':rows,'raceAccepted':len(accepted),'raceBusy':8-len(accepted)}),flush=True)
finally:os.close(fd)
