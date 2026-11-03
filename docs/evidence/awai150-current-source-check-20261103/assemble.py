from pathlib import Path
import re
repo=Path('/Users/junkawasaki/github/workspaces/codex/awai150-inference-replay-guard');prior=Path('/private/tmp/awai150-native-cron-unity.kotoba');out=Path('/private/tmp/awai150-closure-refresh')
def parse(s):
 a=[];i=0;n=len(s)
 while i<n:
  if s[i]==';':
   j=s.find('\n',i);i=n if j<0 else j+1;continue
  if s[i]!='(':i+=1;continue
  start=i;depth=0;string=False;escape=False;comment=False
  while i<n:
   c=s[i]
   if comment:
    if c=='\n':comment=False
   elif string:
    if escape:escape=False
    elif c=='\\':escape=True
    elif c=='"':string=False
   elif c==';':comment=True
   elif c=='"':string=True
   elif c=='(':depth+=1
   elif c==')':
    depth-=1
    if depth==0:i+=1;break
   i+=1
  form=s[start:i];m=re.match(r'\(defn-?\s+([^\s\[()]+)',form)
  if m:a.append((m.group(1),start,i,form))
 return a
files=['kotoba/git_checkpoint.kotoba','kotoba/profile_recovery.kotoba','kotoba/profile_resident.kotoba','kotoba/profile_cron_bridge.kotoba','kotoba/github_checkpoint_archive.kotoba','kotoba/profile_cron_controller.kotoba']
current={}
for f in files:
 for n,_,_,form in parse((repo/f).read_text()):
  if n in current:raise ValueError(('duplicate-current',n,f))
  current[n]=form
cursor=Path('/Users/junkawasaki/github/workspaces/codex/awai147-cron-cursor/kotoba/itonami/gateway_cursor.kotoba')
for n,_,_,form in parse(cursor.read_text()):
 if not n.startswith('cc-') or n == 'cc-plan': continue
 if n in current:raise ValueError(('duplicate-cursor',n))
 current[n]=form
unity=prior.read_text();forms=parse(unity);names=[a for a,_,_,_ in forms];dups={n for n in names if names.count(n)>1};assert not (dups & current.keys()),dups & current.keys()
missing=set(current)-set(names);print('missing current definitions',sorted(missing))
replacements=[]
for name,a,b,form in forms:
 if name in current:
  new=current[name]
  if name=='cp-exists?':new+='\n'+'\n'.join(current[n] for n in sorted(missing))
  replacements.append((a,b,new))
 if name=='main':replacements.append((a,b,'(defn main [] :i64 (profile-cron-controller-main))'))
assert sum(n=='main' for n in names)==1
for a,b,new in reversed(replacements):unity=unity[:a]+new+unity[b:]
(out/'unity.kotoba').write_text(unity);print('updated',len(current),'functions; bytes',len(unity))
