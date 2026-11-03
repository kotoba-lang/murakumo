import os,pathlib,resource,subprocess
p=pathlib.Path('/private/tmp/awai150-request-unknown');fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
def limit():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
def run(binary,name,head):
 r=subprocess.run([str(p/(binary+'.macho')),'/'+name+'/request',head],cwd=p,pass_fds=(fd,),capture_output=True,text=True,timeout=5,preexec_fn=limit);assert r.returncode==0,(binary,name,r.returncode,r.stderr);return r.stdout.strip()
def setup(name,request=None,tmp=None):
 root=p/name;root.mkdir()
 if request is not None:(root/'request').write_text(request)
 if tmp is not None:(root/'request.tmp').write_text(tmp)
 return root
rows=[('stable','wanted',None,'other','bridge/request-ready'),('partial','wanted','incomplete','other','bridge/request-unknown'),('consumed','wanted',None,'wanted','bridge/already-consumed'),('consumed-partial','wanted','incomplete','wanted','bridge/request-unknown'),('different','different',None,'other','bridge/pending-request'),('missing-partial',None,'incomplete','other','bridge/request-unknown'),('error','wanted',None,'other','bridge/request-unknown')]
try:
 for name,request,tmp,head,expected in rows:
  root=setup(name,request,tmp);before={x.name:x.read_bytes() for x in root.iterdir()};result=run('fixture',name,head);assert result==expected,(name,result);assert before=={x.name:x.read_bytes() for x in root.iterdir()};print(name,result,flush=True)
 for name,head,expected in [('partial','other','bridge/request-ready'),('consumed-partial','wanted','bridge/already-consumed'),('error','other','bridge/request-ready')]:
  result=run('old',name,head);assert result==expected,(name,result);print('old-'+name,result,flush=True)
finally:os.close(fd)
