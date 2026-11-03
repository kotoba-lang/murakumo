# Test-only composition of current lease core and voter with pinned dependencies.
from pathlib import Path
import subprocess
p=Path('/private/tmp/awai150-voter-time-live')
exec(Path('docs/evidence/awai150-lease-time-guard-20261103/generate-recovery.py').read_text().split('base=Path(')[0])
p=Path('/private/tmp/awai150-voter-time-live')
base=forms(Path('docs/evidence/awai150-lease-native-fixture.kotoba').read_text())
node=dict(forms(Path('kotoba/fleet_lease_node.kotoba').read_text()))
core=dict(forms(Path('kotoba/fleet_lease_core.kotoba').read_text()))
oldcore=dict(forms(subprocess.check_output(['git','show','9a1a5ba:kotoba/fleet_lease_core.kotoba'],text=True)))
base_names={k for k,_ in base}; node_needed={k for k in node if k in base_names}; core_needed={k for k in core if k in base_names}
def build(old):
 overrides={k:node[k] for k in node_needed};overrides.update({k:(oldcore if old else core)[k] for k in core_needed})
 # Include newly referenced voter/core helpers from these actual sources.
 available={**core,**node};needed=set(overrides)
 while True:
  extra={t for k in needed for t in tokens(overrides.get(k,available[k])) if t in available and t not in needed}
  if not extra:break
  needed.update(extra)
 for k in needed:
  if k not in overrides:overrides[k]=available[k]
 seen=set();out=[]
 for k,f in base:
  if k not in seen:out.append(overrides.get(k,f));seen.add(k)
 for source in (core,node):
  for k,f in source.items():
   if k in needed and k not in seen:out.append(overrides[k]);seen.add(k)
 assert needed <= seen
 return '(ns lease-live (:export [main]))\n'+'\n'.join(out)+'\n'
(p/'current.kotoba').write_text(build(False));(p/'old-core.kotoba').write_text(build(True))
