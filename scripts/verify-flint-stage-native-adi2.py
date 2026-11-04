#!/usr/bin/env python3
"""Diagnostic: stage synthetic Flint content and read ADI2 using held FDs."""
import argparse,errno,hashlib,importlib.util,io,json,os,pathlib,re,resource,shutil,stat,struct,subprocess,sys,tarfile,tempfile
sys.dont_write_bytecode=True
repo=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('descriptor',repo/'scripts/verify-flint-oci-descriptor-native.py');h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
s=(repo/'docs/evidence/awai149-flint-stage-inventory-20261105/fixture.kotoba').read_text()
files={'fleet_model_flint_oci_stage.kotoba':['ft-name','ft-id','ft-walk','ft-stage','fk-file-name','fk-file-id','fk-manifest-valid?','fk-stage-manifest?','model-flint-oci-stage-main'],'fleet_model_flint_oci_seal.kotoba':['mf-check'],'fleet_model_digest.kotoba':['md-observe','ma-read','md-present?'],'fleet_model_assets.kotoba':['ta-header?','tw-copy','mp-mode?'],'fleet_directory_inventory.kotoba':['di-nibble','di-hex?','di-byte','di-u16','di-u32','di-nonzero-ino?','di-name?','di-id','di-inventory?','di-id-hex?','di-bound-inventory?','model-inventory-main'],'fleet_flint_directory_inventory.kotoba':['fi-root-name','fi-child-name','fi-id','fi-inventory?','flint-bound-inventory?','flint-parent-frame?'],'fleet_flint_verified_inventory.kotoba':['fv-tar-match?','model-flint-verified-inventory?','model-flint-parent-verified-inventory?']}
for file,names in files.items():
 src=(repo/'kotoba'/file).read_text()
 for name in names:
  a,b=h.form(s,name);c,d=h.form(src,name)
  if s[a:b]!=src[c:d]:raise ValueError('source mismatch '+name)
p=argparse.ArgumentParser();p.add_argument('--compiler',type=pathlib.Path,required=True);p.add_argument('--compiler-sha256',required=True);p.add_argument('--packager',type=pathlib.Path,required=True);p.add_argument('--packager-sha256',required=True);p.add_argument('--inventory-helper',type=pathlib.Path,required=True);p.add_argument('--inventory-helper-sha256',required=True);args=p.parse_args()
compiler=args.compiler.resolve(strict=True);pack=args.packager.resolve(strict=True)
helper=args.inventory_helper.resolve(strict=True)
class BindingMismatch(Exception):pass
for path,digest in [(compiler,args.compiler_sha256),(pack,args.packager_sha256),(helper,args.inventory_helper_sha256)]:
 if not re.fullmatch('[0-9a-f]{64}',digest) or hashlib.sha256(path.read_bytes()).hexdigest()!=digest:raise RuntimeError('tool pin mismatch')
subprocess.run(['/usr/bin/codesign','--verify','--strict',str(helper)],check=True,capture_output=True,timeout=15)
metadata=['config.json','tokenizer.json','tokenizer_config.json','chat_template.jinja','generation_config.json','model.safetensors.index.json'];content={n:('fixture '+n+'\n').encode() for n in metadata};base=b'base-weights-fixture'*7;experts=b'expert-weights-fixture'*11
items=[{'path':n,'size':len(content[n])} for n in metadata]+[{'path':'model.safetensors','size':len(base)},{'path':'experts.safetensors','size':len(experts)}]
manifest={'name':'flint-ud-iq2_xxs','kapitsa':'0.2.26','variant':'gguf IQ2_XXS','total_bytes':sum(x['size'] for x in items),'files':items};content['.kapitsa/manifest.json']=json.dumps(manifest,separators=(',',':')).encode()
def digest(b):return 'sha256:'+hashlib.sha256(b).hexdigest()
def limits():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
with tempfile.TemporaryDirectory(prefix='awai149-flint-full-',dir='/private/tmp') as d:
 root=pathlib.Path(d);(root/'fixture.kotoba').write_text(s)
 def archive(parts):
  out=io.BytesIO()
  with tarfile.open(fileobj=out,mode='w',format=tarfile.USTAR_FORMAT) as t:
   for n,b in parts.items():
    v=tarfile.TarInfo(n);v.size=len(b);v.mode=0o600;v.mtime=0;t.addfile(v,io.BytesIO(b))
  return out.getvalue()
 def layout(name,assets):
  loc=root/name;blobs=loc/'blobs/sha256';blobs.mkdir(parents=True);config=b'{}'
  def descriptor(kind,b):return {'mediaType':kind,'digest':digest(b),'size':len(b)}
  m={'artifactType':'application/vnd.awai.model.flint.v1','config':descriptor('application/vnd.oci.empty.v1+json',config),'layers':[descriptor('application/vnd.awai.model.flint.model.v1',base),descriptor('application/vnd.awai.model.flint.experts.v1',experts),descriptor('application/vnd.awai.model.flint.assets.tar.v1',assets)],'mediaType':'application/vnd.oci.image.manifest.v1+json','schemaVersion':2}
  mb=json.dumps(m,sort_keys=True,separators=(',',':')).encode();idx={'manifests':[descriptor('application/vnd.oci.image.manifest.v1+json',mb)],'schemaVersion':2}
  for b in (config,base,experts,assets,mb):(blobs/digest(b)[7:]).write_bytes(b)
  (loc/'oci-layout').write_text('{"imageLayoutVersion":"1.0.0"}');(loc/'index.json').write_text(json.dumps(idx,sort_keys=True,separators=(',',':')))
  return [digest(mb),str(len(mb)),digest(base),str(len(base)),digest(experts),str(len(experts)),digest(assets),str(len(assets))]
 good=layout('layout',archive(content))
 invalid=dict(content);m=dict(manifest);m['total_bytes']+=1;invalid['.kapitsa/manifest.json']=json.dumps(m,separators=(',',':')).encode();bad=layout('badlayout',archive(invalid))
 fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY);saved=None
 if fd!=3:
  try:saved=os.dup(3)
  except OSError:pass
  os.dup2(fd,3)
 def run(args,expected=0):
  r=subprocess.run([str(x) for x in args],cwd=root,env={'PATH':'/nonexistent'},pass_fds=(3,),capture_output=True,timeout=15,preexec_fn=limits)
  if r.returncode!=expected or r.stderr:raise RuntimeError((args,r.returncode,r.stdout[-400:],r.stderr[-400:]))
  return r.stdout.decode(errors='replace').strip()
 try:
  run([compiler,'compile','fixture.kotoba','--target','aarch64-macos','--output','fixture.kseed']);out=run([compiler,'extract-native','fixture.kseed','--symbol','main','--output','fixture.raw']);m=re.search(r':offset (\d+), :length (\d+), :arity 0}',out);assert m
  run([pack,'fixture-unsigned.macho','fixture.raw','fs2-cli-stdio-general',m.group(1)]);img=root/'fixture.macho';shutil.copyfile(root/'fixture-unsigned.macho',img);img.chmod(0o700)
  subprocess.run(['/usr/bin/codesign','--force','--sign','-',str(img)],check=True,capture_output=True,timeout=15);subprocess.run(['/usr/bin/codesign','--verify','--strict',str(img)],check=True,capture_output=True,timeout=15)
  def call(name,layoutname,args,stage,rc,expected):
   out=run([img,'/'+layoutname,*args,'/'+stage],rc)
   if out!=expected:raise RuntimeError((name,out,expected))
   print(name,out,flush=True)
  call('valid','layout',good,'stage',0,'flint-artifact/staged-file-list-verified')
  for n,b in {**content,'model.safetensors':base,'experts.safetensors':experts}.items():
   p=root/'stage'/n
   if p.read_bytes()!=b or p.stat().st_mode&0o777!=0o600:raise RuntimeError('file mismatch '+n)
  def snapshot(fd):
   savedfd=os.dup(3)
   try:
    os.dup2(fd,3)
    r=subprocess.run([str(helper)],cwd=root,env={'PATH':'/nonexistent'},pass_fds=(3,),capture_output=True,timeout=15,preexec_fn=limits)
    if r.returncode or r.stderr:raise RuntimeError(('ADI2 helper failed',r.returncode,r.stderr[-200:]))
    frame=r.stdout.decode('ascii')
    if not frame.startswith('41444932') or not frame.endswith('\n'):raise RuntimeError('bad ADI2 frame')
    return frame
   finally:os.dup2(savedfd,3);os.close(savedfd)
  def verify(name,layoutname,args,stage,rc,expected,bad_parent=False,bad_root=False,observed_stage=None,enforce_binding=True):
   flags=os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW
   observed=stage if observed_stage is None else observed_stage
   rootfd=os.open(root/observed,flags);childfd=None
   try:
    childfd=os.open('.kapitsa',flags,dir_fd=rootfd)
    rs=os.fstat(rootfd);cs=os.fstat(childfd);entry=os.stat('.kapitsa',dir_fd=rootfd,follow_symlinks=False)
    if (entry.st_dev,entry.st_ino)!=(cs.st_dev,cs.st_ino):raise RuntimeError('child identity changed')
    target=os.stat(root/stage,follow_symlinks=False)
    if enforce_binding and (not stat.S_ISDIR(target.st_mode) or (target.st_dev,target.st_ino)!=(rs.st_dev,rs.st_ino)):raise BindingMismatch('stage path differs from held root FD')
    ident=lambda n:struct.pack('<Q',n).hex()
    parent=(b'ADP1'+b'\0'*4+struct.pack('<QQQQ',rs.st_dev,rs.st_ino,cs.st_dev,cs.st_ino)).hex()+'\n'
    if bad_parent:parent='4144503101000000'+parent[16:]
    f=[parent,snapshot(rootfd),ident(rs.st_dev),ident(rs.st_ino),snapshot(childfd),ident(cs.st_ino)]
    if bad_root:
     f[3]='ffffffffffffffff'
     if f[3]==ident(rs.st_ino):raise RuntimeError('bad root control is not distinct')
     f[0]=f[0][:32]+f[3]+f[0][48:]
    out=run([img,'verify','/'+layoutname,*args,'/'+stage,*f],rc)
    if out!=expected:raise RuntimeError((name,out,expected))
    entry_after=os.stat('.kapitsa',dir_fd=rootfd,follow_symlinks=False);root_after=os.stat(root/observed,follow_symlinks=False)
    if (os.fstat(rootfd).st_dev,os.fstat(rootfd).st_ino)!=(rs.st_dev,rs.st_ino) or (os.fstat(childfd).st_dev,os.fstat(childfd).st_ino)!=(cs.st_dev,cs.st_ino) or (entry_after.st_dev,entry_after.st_ino)!=(cs.st_dev,cs.st_ino) or (root_after.st_dev,root_after.st_ino)!=(rs.st_dev,rs.st_ino):raise RuntimeError('FD or path identity changed')
    target_after=os.stat(root/stage,follow_symlinks=False)
    if enforce_binding and (not stat.S_ISDIR(target_after.st_mode) or (target_after.st_dev,target_after.st_ino)!=(rs.st_dev,rs.st_ino)):raise BindingMismatch('stage path changed during verification')
    print(name,out,flush=True)
   finally:
    if childfd is not None:os.close(childfd)
    os.close(rootfd)
  verify('verified-stage','layout',good,'stage',0,'inventory/verified')
  target=root/'stage'/'config.json';original=target.read_bytes()
  try:
   target.write_bytes(bytes([original[0]^1])+original[1:])
   verify('changed-metadata','layout',good,'stage',1,'inventory/refused')
  finally:target.write_bytes(original)
  target=root/'stage'/'model.safetensors';original=target.read_bytes()
  try:
   target.write_bytes(bytes([original[0]^1])+original[1:])
   verify('changed-staged-weight','layout',good,'stage',1,'inventory/refused')
  finally:target.write_bytes(original)
  (root/'stage'/'extra').write_bytes(b'extra')
  try:verify('extra-entry','layout',good,'stage',1,'inventory/refused')
  finally:(root/'stage'/'extra').unlink()
  verify('wrong-parent-frame','layout',good,'stage',1,'inventory/refused',True)
  verify('wrong-root-identity','layout',good,'stage',1,'inventory/refused',False,True)
  childpath=root/'stage'/'.kapitsa';heldpath=root/'stage'/'.kapitsa-held'
  childpath.rename(heldpath)
  try:
   childpath.symlink_to('.kapitsa-held')
   try:
    verify('symlink-child','layout',good,'stage',1,'inventory/refused')
   except OSError as error:
    if error.errno not in (errno.ELOOP,errno.ENOTDIR):raise
    print('symlink-child refused by no-follow open',flush=True)
   else:raise RuntimeError('symlink child unexpectedly opened')
  finally:
   if childpath.is_symlink():childpath.unlink()
   heldpath.rename(childpath)
  verify('restored-stage','layout',good,'stage',0,'inventory/verified')
  shutil.copytree(root/'stage',root/'other-stage')
  (root/'other-stage'/'extra').write_bytes(b'extra')
  verify('other-stage-own-inventory','layout',good,'other-stage',1,'inventory/refused')
  # Deliberate host-binding failure: the frame/FDs refer to stage while the
  # verifier reads other-stage. Success is the counterexample, not approval.
  verify('unbound-inventory-counterexample','layout',good,'other-stage',0,'inventory/verified',observed_stage='stage',enforce_binding=False)
  try:
   verify('host-bound-other-stage','layout',good,'other-stage',0,'inventory/verified',observed_stage='stage')
  except BindingMismatch:
   print('host-bound-other-stage refused before verifier',flush=True)
  else:raise RuntimeError('host accepted mismatched stage path')
  call('bad-manifest','badlayout',bad,'badstage',1,'flint-artifact/staging-unknown')
  verify('partial-stage','badlayout',bad,'badstage',1,'inventory/refused')
  wrong=good.copy();wrong[2]='sha256:'+'0'*64;call('wrong-pin','layout',wrong,'wrongstage',1,'flint-artifact/input-refused')
  call('overlap','layout',good,'layout/nested',1,'flint-artifact/input-refused')
  for name,index,original in [('base',2,base),('experts',4,experts)]:
   path=root/'layout'/'blobs'/'sha256'/good[index][7:]
   try:
    path.write_bytes(bytes([original[0]^1])+original[1:])
    call('corrupt-'+name,'layout',good,'corrupt-'+name,1,'flint-artifact/staging-unknown')
   finally:path.write_bytes(original)

  print('nine-files-match',True,'source-sha256',hashlib.sha256(s.encode()).hexdigest(),'image-sha256',hashlib.sha256(img.read_bytes()).hexdigest(),flush=True)
 finally:
  if fd!=3:
   if saved is None:os.close(3)
   else:os.dup2(saved,3);os.close(saved)
  os.close(fd)
