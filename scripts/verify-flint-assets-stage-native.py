#!/usr/bin/env python3
"""Exercise selected Flint tar staging on synthetic files; not a deployment."""
import argparse,hashlib,importlib.util,io,os,pathlib,re,resource,shutil,subprocess,sys,tarfile,tempfile
sys.dont_write_bytecode=True
repo=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('descriptor',repo/'scripts/verify-flint-oci-descriptor-native.py');h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
s=(repo/'docs/evidence/awai149-flint-assets-stage-20261105/fixture.kotoba').read_text();src=(repo/'kotoba/fleet_model_flint_oci_stage.kotoba').read_text()
for name in ('ft-name','ft-id','ft-walk','ft-stage'):
 a,b=h.form(s,name);c,d=h.form(src,name)
 if s[a:b]!=src[c:d]:raise ValueError('source mismatch '+name)
p=argparse.ArgumentParser();p.add_argument('--compiler',type=pathlib.Path,required=True);p.add_argument('--compiler-sha256',required=True);p.add_argument('--packager',type=pathlib.Path,required=True);p.add_argument('--packager-sha256',required=True);args=p.parse_args()
compiler=args.compiler.resolve(strict=True);pack=args.packager.resolve(strict=True)
for path,digest in [(compiler,args.compiler_sha256),(pack,args.packager_sha256)]:
 if not re.fullmatch('[0-9a-f]{64}',digest) or hashlib.sha256(path.read_bytes()).hexdigest()!=digest:raise RuntimeError('tool pin mismatch')
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
  if (root/'escape').exists():raise RuntimeError('unexpected escaped path')

 finally:
  if fd!=3:
   if saved is None:os.close(3)
   else:os.dup2(saved,3);os.close(saved)
  os.close(fd)
