# Test-only: compose actual resident and group functions with known dependency unity.
from pathlib import Path
p=Path('/private/tmp/awai150-resident-quorum')
exec(Path('docs/evidence/awai150-lease-time-guard-20261103/generate-recovery.py').read_text().split('base=Path(')[0])
p=Path('/private/tmp/awai150-resident-quorum')
fs=forms(Path('docs/evidence/awai150-current-cron-unity-20261103/unity.kotoba').read_text());base=dict(fs)
modules=('fleet_lease_core','fleet_lease_node','fleet_lease_group','profile_resident','recovery_admission','git_checkpoint','fenced_profile_recovery')
for module in modules:
 for k,v in forms(Path('kotoba/'+module+'.kotoba').read_text()):base[k]=v
mains={
'group':'''(defn main [] :i64 (if (= (cp-arg 0) "init") (let [r (ln-init-fresh (cp-arg 1)) x (cp-say r)] (if (= r "voter/init-created") 0 1)) (lease-group-main)))''',
'resident':'''(defn main [] :i64 (let [c (cp-read (cp-arg 0)) v (rt-current-lease c 1) x (cp-say (if (= (vector-count v) 0) "no-lease" (cp-text v)))] (if (= (vector-count v) 0) 1 0)))'''
}
for name,main in mains.items():
 pairs=dict(base);pairs['main']=main;need={'main'};stack=['main']
 while stack:
  for k in tokens(pairs[stack.pop()]):
   if k in pairs and k not in need:need.add(k);stack.append(k)
 out=[];seen=set()
 # definitions can refer to later definitions, preserving existing unity order.
 for k,_ in fs:
  if k in need and k!='main' and k not in seen:out.append(pairs[k]);seen.add(k)
 for k in sorted(need-seen-{'main'}):out.append(pairs[k]);seen.add(k)
 assert not (need-seen-{'main'})
 (p/(name+'.kotoba')).write_text('(ns live-test (:export [main]))\n'+'\n'.join(out)+'\n'+main+'\n')
 print(name,'definitions',len(need),'bytes',(p/(name+'.kotoba')).stat().st_size)
import subprocess
s=(p/'group.kotoba').read_text()
a=dict(forms(Path('kotoba/fleet_lease_core.kotoba').read_text()))['valid-value?']
b=dict(forms(subprocess.check_output(['git','show','9a1a5ba:kotoba/fleet_lease_core.kotoba'],text=True)))['valid-value?']
assert s.count(a)==1
(p/'old-group.kotoba').write_text(s.replace(a,b))
s=(p/'resident.kotoba').read_text();i=s.rindex('(defn main []')
main='''(defn main [] :i64 (let [c (cp-read (cp-arg 0)) r (lease-group-round 1 (cp-str c 0 "node") (lease-text (cp-str c 0 "node") (cp-str c 0 "task") 0 1 -1 1) (cp-str c 0 "voters") (lease-token (cp-str c 0 "tokenFile"))) x (cp-say r)] (if (= r "") 1 0)))\n'''
(p/'seed-bad.kotoba').write_text(s[:i]+main)
