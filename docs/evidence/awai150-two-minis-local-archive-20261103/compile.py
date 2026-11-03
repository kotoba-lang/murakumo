import pathlib,os,resource,subprocess,re,json
p=pathlib.Path('/private/tmp/awai150-mini-archive-4bac682e05');env=dict(os.environ,KEXE_CAP_RESOURCES_35=str(p))
def limit():resource.setrlimit(resource.RLIMIT_CPU,(90,90))
rows={}
for name in ('fixture','config-fixture'):
 for args in (['compile',name+'.kotoba','--target','aarch64-macos','--output',name+'.kseed'],['extract-native',name+'.kseed','--symbol','main','--output',name+'.raw']):
  r=subprocess.run([str(p/'root.elf'),*args],cwd=p,env=env,capture_output=True,text=True,timeout=100,preexec_fn=limit);print(name,args[0],r.returncode,r.stdout.strip(),r.stderr[-300:],flush=True);assert r.returncode==0
  if args[0]=='extract-native':
   m=re.search(r':offset (\d+), :length (\d+), :arity 0',r.stdout);assert m and int(m.group(2))==(p/(name+'.raw')).stat().st_size;rows[name]=int(m.group(1))
(p/'offsets.json').write_text(json.dumps(rows,sort_keys=True))
