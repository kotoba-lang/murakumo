from pathlib import Path
repo=Path('/Users/junkawasaki/github/workspaces/codex/awai150-inference-replay-guard');p=Path('/private/tmp/awai150-current-mac-checkpoint')
fixture=(repo/'docs/evidence/awai154-mac-checkpoint-end-to-end-20261103/fixture.kotoba').read_text();current=(repo/'kotoba/git_checkpoint.kotoba').read_text();prefix=fixture[:fixture.index('(defn- cp-bytes ')];adapter=fixture[fixture.index(';; Test-only adapter for a disposable local fixture.'):fixture.index('(defn- cp-out ')]
start=current.index('(defn- cp-bytes ');body=current[start:];old='(defn- cp-spawn [s :string] :string (typed-cap-call 20 :string :string s))';assert body.count(old)==1
adapter=adapter.replace('/private/tmp/awai154-fs2-darwin-gate',str(p)).replace('/fixture3/','/fixture4/');adapter=adapter.replace('fixture3"','fixture4"');body=body.replace(old,adapter)
(p/'fixture.kotoba').write_text(prefix+body+'\n(defn lower [c :i64] :i64 (if (and (>= c 65) (<= c 90)) (+ c 32) c))\n(defn main [] :i64 (checkpoint-main))\n')
