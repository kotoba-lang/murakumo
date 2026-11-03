# Test-only source compilation and packaging; never installs a runtime.
import os,pathlib,re,resource,shutil,subprocess
p=pathlib.Path('/private/tmp/awai150-head-cas');fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
root='/tmp/codex-amu-1762/seed/bootstrap/aarch64-macos/amu-full-root.macho';pack='/private/tmp/awai154-host-git-env/candidate.macho'
def limits():resource.setrlimit(resource.RLIMIT_CPU,(10,10))
def run(cmd):
 r=subprocess.run(cmd,cwd=p,pass_fds=(fd,),capture_output=True,timeout=25,env={'PATH':'/nonexistent'},preexec_fn=limits);assert r.returncode==0,(cmd,r.returncode,r.stdout[-400:],r.stderr[-400:]);return r.stdout
try:
 for name in ('lost',):
  run([root,'compile',name+'.kotoba','--target','aarch64-macos','--output',name+'.kseed'])
  raw=run([root,'extract-native',name+'.kseed','--symbol','main','--output',name+'.raw'])
  m=re.search(rb':offset (\d+), :length (\d+), :arity 0\}',raw);assert m and int(m.group(2))==(p/(name+'.raw')).stat().st_size
  run([pack,name+'-unsigned.macho',name+'.raw','amu-cli-host',m.group(1).decode()])
  out=p/(name+'.macho');shutil.copyfile(p/(name+'-unsigned.macho'),out);os.chmod(out,0o700)
  subprocess.run(['/usr/bin/codesign','--force','--sign','-',str(out)],check=True,capture_output=True)
  print('built',name,flush=True)
finally:os.close(fd)
