import fcntl,os,pathlib,subprocess,json
p=pathlib.Path.cwd();f=p/'case-lock';f.mkdir();(f/'queue').write_text('RGQ1\n');(f/'queue.lock').write_text('')
fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
lock=os.open(f/'queue.lock',os.O_RDWR);fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
try:
 r=subprocess.run([str(p/'fifo.macho'),'/case-lock','front'],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5)
 print(json.dumps({'exit':r.returncode,'stdout':r.stdout,'stderr':r.stderr}),flush=True)
 assert (r.returncode,r.stdout)==(1,'fifo/busy\n')
finally:fcntl.flock(lock,fcntl.LOCK_UN);os.close(lock);os.close(fd)
