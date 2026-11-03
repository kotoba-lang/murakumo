# Mutate only the reply after a real local head push; the push still runs.
from pathlib import Path
p=Path('/private/tmp/awai150-head-cas');s=(p/'fixture.kotoba').read_text()
old='(ga-git dir (str "push" (cp-a) "--quiet" (cp-a) remote (cp-a) "HEAD:" ref))';assert s.count(old)==1
(p/'lost.kotoba').write_text(s.replace(old,'(let [actual '+old+'] (if (cp-ok? actual) "1\\n" actual))'))
