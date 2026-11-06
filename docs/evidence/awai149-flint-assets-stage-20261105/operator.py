import hashlib,importlib.util,io,os,pathlib,re,resource,shutil,subprocess,sys,tarfile,tempfile
sys.dont_write_bytecode=True
repo=pathlib.Path('/Users/junkawasaki/github/workspaces/codex/awai149-oci-seal')
spec=importlib.util.spec_from_file_location('h','/Users/junkawasaki/github/workspaces/codex/awai147-job-archive-cursor/scripts/native/check-cron-decisions.py');h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
s=pathlib.Path('/private/tmp/awai-flint-oci-stage-check.kotoba').read_text();src=(repo/'kotoba/fleet_model_flint_oci_stage.kotoba').read_text()
for name in ('ft-name','ft-id','ft-walk','ft-stage'):
 a,b=h.form(s,name);c,d=h.form(src,name);s=s[:a]+src[c:d]+s[b:]
a,b=h.form(s,'main');s=s[:a]+'''(defn main [] :i64
 (let [result (ft-stage (md-arg 0) (md-arg 1) (md-arg 2) (md-decimal (md-arg 3))) x (md-out result)]
  (if (= result "flint-assets/staged") 0 1)))'''+s[b:]
pathlib.Path('/private/tmp/awai149-flint-stage-fixture.kotoba').write_text(s)
compiler=pathlib.Path('/tmp/codex-amu-1762/seed/bootstrap/aarch64-macos/amu-full-root.macho');pack=pathlib.Path('/private/tmp/awai-oci-range/candidate.macho')
for path,digest in [(compiler,'920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6'),(pack,'df0e94211a35d84901ebcf2a8620cb653140d957732474ff77bd0cdd5b5bf4c5')]:
 if hashlib.sha256(path.read_bytes()).hexdigest()!=digest:raise RuntimeError('tool pin mismatch')
files=['config.json','tokenizer.json','tokenizer_config.json','chat_template.jinja','generation_config.json','model.safetensors.index.json','.kapitsa/manifest.json'];content={n:('fixture '+n+'\n').encode() for n in files}
def limits():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
with tempfile.TemporaryDirectory(prefix='awai149-flint-stage-',dir='/private/tmp') as d:
 root=pathlib.Path(d);(root/'fixture.kotoba').write_text(s)
 with tarfile.open(root/'assets.tar','w',format=tarfile.USTAR_FORMAT) as t:
  for n,b in content.items():
   v=tarfile.TarInfo(n);v.size=len(b);v.mode=0o600;v.mtime=0;t.addfile(v,io.BytesIO(b))
 data=(root/'assets.tar').read_bytes();digest='sha256:'+hashlib.sha256(data).hexdigest();fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY);saved=None
 if fd!=3:
  try:saved=os.dup(3)
  except OSError:pass
  os.dup2(fd,3)
 def run(args,expected=0):
  r=subprocess.run([str(x) for x in args],cwd=root,env={'PATH':'/nonexistent'},pass_fds=(3,),capture_output=True,timeout=15,preexec_fn=limits)
  if r.returncode!=expected or r.stderr:raise RuntimeError((args,r.returncode,r.stdout[-400:],r.stderr[-400:]))
  return r.stdout
 try:
  run([compiler,'compile','fixture.kotoba','--target','aarch64-macos','--output','fixture.kseed']);out=run([compiler,'extract-native','fixture.kseed','--symbol','main','--output','fixture.raw']);m=re.search(rb':offset (\d+), :length (\d+), :arity 0}',out);assert m
  run([pack,'fixture-unsigned.macho','fixture.raw','fs2-cli-stdio-general',m.group(1).decode()]);img=root/'fixture.macho';shutil.copyfile(root/'fixture-unsigned.macho',img);img.chmod(0o700)
  subprocess.run(['/usr/bin/codesign','--force','--sign','-',str(img)],check=True,capture_output=True,timeout=15);subprocess.run(['/usr/bin/codesign','--verify','--strict',str(img)],check=True,capture_output=True,timeout=15)
  out=run([img,'/assets.tar','/stage',digest,str(len(data))]);print('valid',out.decode().strip(),flush=True)
  for name,b in content.items():
   p=root/'stage'/name
   if p.read_bytes()!=b or p.stat().st_mode&0o777!=0o600:raise RuntimeError('file mismatch '+name)
  print('seven-files-match',True,'tar-size',len(data),'tar-sha256',digest,'source-sha256',hashlib.sha256(s.encode()).hexdigest(),'image-sha256',hashlib.sha256(img.read_bytes()).hexdigest(),flush=True)
  def fail(name,tarname,stage,pin,size,expected):
   out=run([img,'/'+tarname,'/'+stage,pin,str(size)],1).decode().strip()
   if out!=expected:raise RuntimeError((name,out,expected))
   print('refused',name,out,flush=True)
  fail('existing','assets.tar','stage',digest,len(data),'flint-assets/input-refused')
  fail('wrong-digest','assets.tar','wrong-digest','sha256:'+'0'*64,len(data),'flint-assets/staging-unknown')
  def make_bad(name,entries,link=False):
   path=root/(name+'.tar')
   with tarfile.open(path,'w',format=tarfile.USTAR_FORMAT) as t:
    for n in entries:
     b=content.get(n,b'bad');v=tarfile.TarInfo(n);v.size=len(b);v.mode=0o600;v.mtime=0
     if link and n=='config.json':v.type=tarfile.SYMTYPE;v.linkname='tokenizer.json';v.size=0;t.addfile(v)
     else:t.addfile(v,io.BytesIO(b))
   raw=path.read_bytes();return path.name,'sha256:'+hashlib.sha256(raw).hexdigest(),len(raw)
  for name,entries,link in [('missing',files[:-1],False),('duplicate',files+[files[0]],False),('traversal',files+['../escape'],False),('symlink',files,True)]:
   tarname,pin,size=make_bad(name,entries,link);fail(name,tarname,'bad-'+name,pin,size,'flint-assets/staging-unknown')
  if (root.parent/'escape').exists():raise RuntimeError('unexpected external path')

 finally:
  if fd!=3:
   if saved is None:os.close(3)
   else:os.dup2(saved,3);os.close(saved)
  os.close(fd)
