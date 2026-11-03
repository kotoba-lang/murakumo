import os,pathlib,resource,subprocess
p=pathlib.Path('/private/tmp/awai150-request-read-unknown');fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
def limit():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
def run(bin,name,head):
 r=subprocess.run([str(p/(bin+'.macho')),'/'+name+'/request',head],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5,preexec_fn=limit);assert r.returncode==0,(bin,name,r.stderr);return r.stdout.strip()
rows=[('missing',None,'other','bridge/request-ready'),('matching','wanted','other','bridge/request-ready'),('consumed','wanted','wanted','bridge/already-consumed'),('different','different','other','bridge/pending-request'),('invalid','bad','bad','bridge/request-unknown'),('empty','','','bridge/request-unknown'),('read-error','wanted','','bridge/request-unknown'),('status-unknown','other','other','bridge/request-unknown')]
try:
 for name,data,head,expected in rows:
  root=p/name;root.mkdir()
  if data is not None:(root/'request').write_text(data)
  before={x.name:x.read_bytes() for x in root.iterdir()};actual=run('current',name,head);assert actual==expected,(name,actual);assert before=={x.name:x.read_bytes() for x in root.iterdir()};print(name,actual,flush=True)
 for name,head in [('invalid','bad'),('empty',''),('read-error',''),('status-unknown','other')]:
  actual=run('previous',name,head);print('previous-'+name,actual,flush=True);assert actual=='bridge/request-ready',(name,actual)
finally:os.close(fd)
