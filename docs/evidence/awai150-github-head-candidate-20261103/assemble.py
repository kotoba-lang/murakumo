# One-off diagnostic fixture. Replaces GitHub privacy and transport only.
from pathlib import Path
p=Path('/private/tmp/awai150-head-cas');repo=Path('/Users/junkawasaki/github/workspaces/codex/awai150-inference-replay-guard')
s=(repo/'docs/evidence/awai150-mac-local-archive-20261103/fixture.kotoba').read_text().replace('/private/tmp/awai150-mac-archive-flow',str(p));s=s[:s.index('(defn- ga-repo?')]
g=(repo/'kotoba/github_checkpoint_archive.kotoba').read_text();g=g[g.index('(defn- ga-repo?'):];a=g.index('(defn- ga-private?');b=g.index('\n;; Reject effective Git configuration',a);g=g[:a]+'''(defn- ga-private? [gh :string repo :string] :bool (= repo "fixture/private"))
'''+g[b:]
h=(repo/'kotoba/github_checkpoint_head.kotoba').read_text();h=h[h.index('(defn- gh-head-text'):]
old='(str "https://github.com/" repo ".git")';assert g.count(old)==2 and h.count(old)==1
remote='"file:///private/tmp/awai150-head-cas/fixture5/remote.git"';g=g.replace(old,remote);h=h.replace(old,remote)
main='''(defn main [] :i64 (if (= (cp-arg 0) "advance")
 (let [v (gh-advance (cp-arg 1) (cp-arg 2) (cp-arg 3) (cp-arg 4) (cp-arg 5) (cp-arg 6) (cp-arg 7) (cp-int (cp-arg 8)) (cp-arg 9) (cp-arg 10) (cp-arg 11) (cp-arg 12) (cp-arg 13)) x (cp-say v)] (if (= (string-index-of v "head/advanced ") 0) 0 1))
 (checkpoint-main)))\n'''
(p/'fixture.kotoba').write_text(s+g+h+main)
