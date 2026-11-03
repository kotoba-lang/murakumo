# One-off test observer for a single cancellation/dequeue race.
import concurrent.futures,json,os,pathlib,subprocess
p=pathlib.Path('/private/tmp/awai151-fifo-cancel');r=p/'race';r.mkdir();(r/'queue.lock').write_text('')
a='a'*64;b='b'*64
(r/'queue').write_text('RGQ1\n'+a+'\n'+b+'\n')
fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
def run(pair):
 x=subprocess.run([str(p/'fifo.macho'),'/race',*pair],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=8)
 return [x.returncode,x.stdout.strip()]
try:
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as e:out=list(e.map(run,[('dequeue',a),('cancel',b)]))
 state=(r/'queue').read_text(); assert out[0] in ([0,'fifo/dequeued'],[1,'fifo/busy']) and out[1] in ([0,'fifo/cancelled'],[1,'fifo/cancel-front-refused'],[1,'fifo/busy'])
 if out[0][0]==0 and out[1][0]==0: assert state=='RGQ1\n'
 elif out[0][0]==0: assert state=='RGQ1\n'+b+'\n'
 elif out[1][0]==0: assert state=='RGQ1\n'+a+'\n'
 else: raise AssertionError('neither operation succeeded')
 print(json.dumps({'dequeue':out[0],'cancel':out[1],'remaining':len(state.splitlines())-1}))
finally:os.close(fd)
