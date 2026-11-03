# Test-only: add missing JSON validator functions from a prior unity fixture.
from pathlib import Path
exec(Path('docs/evidence/awai150-lease-time-guard-20261103/generate-recovery.py').read_text().split('base=Path(')[0])
p=Path('/private/tmp/awai150-head-restore');s=(p/'fixture.kotoba').read_text();existing=dict(forms(s));allf=dict(forms(Path('docs/evidence/awai150-current-cron-unity-20261103/unity.kotoba').read_text()));need={'json-valid?'};stack=['json-valid?']
while stack:
 k=stack.pop()
 for t in tokens(allf[k]):
  if t in allf and t not in existing and t not in need:need.add(t);stack.append(t)
add=[allf[k] for k in sorted(need)];i=s.rindex('(defn main []');(p/'fixture.kotoba').write_text(s[:i]+'\n'.join(add)+'\n'+s[i:]);print('added json functions',len(add))
