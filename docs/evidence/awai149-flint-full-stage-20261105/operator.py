import hashlib,importlib.util,io,json,os,pathlib,re,resource,shutil,subprocess,sys,tarfile,tempfile
sys.dont_write_bytecode=True
repo=pathlib.Path('/Users/junkawasaki/github/workspaces/codex/awai149-oci-seal')
spec=importlib.util.spec_from_file_location('h',repo/'scripts/verify-flint-oci-descriptor-native.py');h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
s=(repo/'docs/evidence/awai149-flint-assets-stage-20261105/fixture.kotoba').read_text()
for file,names in {'fleet_model_flint_oci_stage.kotoba':['ft-name','ft-id','ft-walk','ft-stage','fk-file-name','fk-file-id','fk-manifest-valid?','fk-stage-manifest?','model-flint-oci-stage-main'],'fleet_model_flint_oci_seal.kotoba':['mf-check']}.items():
 src=(repo/'kotoba'/file).read_text()
 for name in names:
  a,b=h.form(src,name);replacement=src[a:b]
  try:c,d=h.form(s,name);s=s[:c]+replacement+s[d:]
  except ValueError:s+='\n'+replacement+'\n'
a,b=h.form(s,'main');s=s[:a]+'(defn main [] :i64 (model-flint-oci-stage-main))'+s[b:]
pathlib.Path('/private/tmp/awai149-flint-full-stage-fixture.kotoba').write_text(s)
compiler=pathlib.Path('/tmp/codex-amu-1762/seed/bootstrap/aarch64-macos/amu-full-root.macho');pack=pathlib.Path('/private/tmp/awai-oci-range/candidate.macho')
for path,digest in [(compiler,'920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6'),(pack,'df0e94211a35d84901ebcf2a8620cb653140d957732474ff77bd0cdd5b5bf4c5')]:
 if hashlib.sha256(path.read_bytes()).hexdigest()!=digest:raise RuntimeError('tool pin mismatch')
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
  call('bad-manifest','badlayout',bad,'badstage',1,'flint-artifact/staging-unknown')
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
