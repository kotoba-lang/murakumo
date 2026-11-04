#!/usr/bin/env python3
"""Selected-source diagnostic; not an OCI publisher, model test, or governor."""
import argparse,hashlib,os,re,resource,shutil,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def form(source,name):
 match=re.search(r'^\(defn-?\s+'+re.escape(name)+r'(?=[\s\[])',source,re.M)
 if not match:raise ValueError('missing form '+name)
 depth=0;quoted=False;escaped=False;comment=False
 for i in range(match.start(),len(source)):
  c=source[i]
  if comment:
   if c=='\n':comment=False
  elif quoted:
   if escaped:escaped=False
   elif c=='\\':escaped=True
   elif c=='"':quoted=False
  elif c==';':comment=True
  elif c=='"':quoted=True
  elif c=='(':depth+=1
  elif c==')':
   depth-=1
   if depth==0:return match.start(),i+1
 raise ValueError('unclosed form '+name)

def main():
 p=argparse.ArgumentParser()
 p.add_argument('--compiler',type=Path,required=True);p.add_argument('--compiler-sha256',required=True)
 p.add_argument('--packager',type=Path,required=True);p.add_argument('--packager-sha256',required=True)
 a=p.parse_args();compiler=a.compiler.resolve(strict=True);packager=a.packager.resolve(strict=True)
 digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
 for path,pin in ((compiler,a.compiler_sha256),(packager,a.packager_sha256)):
  if not re.fullmatch('[0-9a-f]{64}',pin) or digest(path)!=pin:raise ValueError('tool pin mismatch')
 code=(ROOT/'docs/evidence/awai149-flint-oci-descriptor-20261105/fixture.kotoba').read_text()
 files={'fleet_model_flint_oci_seal.kotoba':['mf-manifest'],'fleet_model_oci_seal.kotoba':['ms-digest','ms-descriptor'],'fleet_model_digest.kotoba':['ma-layer?'],'fleet_model_flint_oci_seal_test.kotoba':['test-flint-oci-descriptors']}
 for path,names in files.items():
  source=(ROOT/'kotoba'/path).read_text()
  for name in names:
   x,y=form(code,name);u,v=form(source,name)
   if code[x:y]!=source[u:v]:raise ValueError('source mismatch '+name)
 x,y=form(code,'mf-manifest');needle='application/vnd.awai.model.flint.experts.v1'
 if code[x:y].count(needle)!=1:raise ValueError('mutant anchor mismatch')
 mutant=code[:x]+code[x:y].replace(needle,'application/vnd.awai.model.bonsai.mtp.v1')+code[y:]
 def limit():resource.setrlimit(resource.RLIMIT_CPU,(3,3))
 with tempfile.TemporaryDirectory(prefix='awai149-flint-descriptor-',dir='/private/tmp') as td:
  root=Path(td);fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY);saved=None
  if fd!=3:
   try:saved=os.dup(3)
   except OSError:pass
   os.dup2(fd,3)
  def run(argv,expected=0):
   r=subprocess.run([str(x) for x in argv],cwd=root,env={'PATH':'/nonexistent'},pass_fds=(3,),capture_output=True,timeout=15,preexec_fn=limit)
   if r.returncode!=expected or r.stderr:raise RuntimeError((r.returncode,r.stdout[-300:],r.stderr[-300:]))
   return r.stdout
  try:
   for name,text,expected in [('descriptor',code,0),('media-mutant',mutant,1)]:
    (root/(name+'.kotoba')).write_text(text)
    run([compiler,'compile',name+'.kotoba','--target','aarch64-macos','--output',name+'.kseed'])
    output=run([compiler,'extract-native',name+'.kseed','--symbol','main','--output',name+'.raw'])
    match=re.search(rb':offset (\d+), :length (\d+), :arity 0}',output)
    if not match or int(match.group(2))!=(root/(name+'.raw')).stat().st_size:raise ValueError('extract mismatch')
    run([packager,name+'.unsigned',name+'.raw','fs2-cli-stdio-general',match.group(1).decode()])
    image=root/(name+'.macho');shutil.copyfile(root/(name+'.unsigned'),image);image.chmod(0o700)
    subprocess.run(['/usr/bin/codesign','--force','--sign','-',str(image)],check=True,capture_output=True,timeout=15)
    subprocess.run(['/usr/bin/codesign','--verify','--strict',str(image)],check=True,capture_output=True,timeout=15)
    run([image],expected)
    print(name,'source-sha256',hashlib.sha256(text.encode()).hexdigest(),'image-sha256',digest(image),flush=True)
  finally:
   if fd!=3:
    if saved is None:os.close(3)
    else:os.dup2(saved,3);os.close(saved)
   os.close(fd)
if __name__=='__main__':main()
