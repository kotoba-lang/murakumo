# One-off synthetic test observer. No resource admission or real workloads.
import concurrent.futures, os, pathlib, subprocess
p=pathlib.Path('/private/tmp/awai151-fifo-gate'); root=p/'synthetic-case-v6'; root.mkdir()
(root/'queue.lock').write_text(''); (root/'queue').write_text('RGQ1\n')
env=dict(os.environ,KEXE_ALLOW='35,37,38',KEXE_CAP_RESOURCES_35=str(p))
def run(op,ticket=None):
 args=[str(p/'fifo.elf'),str(root),op]+([ticket] if ticket is not None else [])
 r=subprocess.run(args,env=env,capture_output=True,text=True,timeout=5)
 return r.returncode,r.stdout.strip(),r.stderr
A='a'*64; B='b'*64; C='c'*64
bad=subprocess.run([str(p/'fifo.elf'),str(root)+'/./x','front'],env=env,capture_output=True,text=True,timeout=5)
assert (bad.returncode,bad.stdout.strip())==(2,'fifo/input-invalid')
assert run('front')[:2]==(0,'fifo/empty')
assert run('enqueue',A)[:2]==(0,'fifo/enqueued')
assert run('enqueue',B)[:2]==(0,'fifo/enqueued')
assert run('enqueue',A)[:2]==(1,'fifo/already-queued')
assert run('front')[:2]==(0,'fifo/front '+A)
assert run('dequeue',B)[:2]==(1,'fifo/not-front')
assert run('dequeue',A)[:2]==(0,'fifo/dequeued')
assert run('front')[:2]==(0,'fifo/front '+B)
(root/'queue.pending').write_text('partial')
assert run('enqueue',C)[:2]==(1,'fifo/pending-unknown')
(root/'queue.pending').unlink()
(root/'queue.intent').write_text('intent\n')
assert run('front')[:2]==(1,'fifo/pending-unknown')
(root/'queue.intent').unlink()
(root/'queue').write_text('RGQ1\n'+B+'\n'+B+'\n')
assert run('front')[:2]==(1,'fifo/state-invalid')
(root/'queue').write_text('RGQ1\n')
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as e:
 results=list(e.map(lambda i: run('enqueue',format(i,'064x')),range(8)))
assert all(x[1] in ('fifo/enqueued','fifo/busy') for x in results),results
accepted=[format(i,'064x') for i,x in enumerate(results) if x[1]=='fifo/enqueued']
lines=(root/'queue').read_text().splitlines()
assert lines[0]=='RGQ1' and len(lines[1:])==len(accepted) and set(lines[1:])==set(accepted)
print('FIFO_OK sequential=7 pending=2 malformed=1 concurrent_attempts=8 accepted='+str(len(accepted)))
