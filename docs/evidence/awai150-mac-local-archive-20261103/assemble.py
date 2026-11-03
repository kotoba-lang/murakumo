from pathlib import Path
p=Path('/private/tmp/awai150-mac-archive-flow');repo=Path('/Users/junkawasaki/github/workspaces/codex/awai150-inference-replay-guard')
s=Path('/private/tmp/awai150-current-mac-checkpoint/fixture.kotoba').read_text().replace('/private/tmp/awai150-current-mac-checkpoint',str(p)).replace('/fixture4/','/fixture5/').replace('fixture4"','fixture5"')
s=s[:s.index('(defn main [] :i64 (checkpoint-main))')]
g=(repo/'kotoba/github_checkpoint_archive.kotoba').read_text();g=g[g.index('(defn- ga-repo? '):];a=g.index('(defn- ga-private? ');b=g.index('\n;; Reject effective Git configuration',a)
g=g[:a]+'''(defn- ga-private? [gh :string repo :string] :bool (= repo "fixture/private"))
'''+g[b:]
old='(str "https://github.com/" repo ".git")';assert g.count(old)==2
g=g.replace(old,'"file:///private/tmp/awai150-mac-archive-flow/fixture5/remote.git"')
main='''\n(defn main [] :i64
 (let [op (cp-arg 0)]
  (cond (= op "archive")
        (let [v (ga-archive (cp-arg 1) (cp-arg 2) (cp-arg 3) (cp-arg 4) (cp-arg 5) (cp-arg 6) (cp-arg 7) (cp-int (cp-arg 8)) (cp-arg 9) (cp-arg 10) (cp-arg 11) (cp-arg 12)) x (cp-say v)] (if (= (string-index-of v "archive/stored ") 0) 0 1))
        (= op "archive-restore")
        (let [v (ga-restore (cp-arg 1) (cp-arg 2) (cp-arg 3) (cp-arg 4) (cp-arg 5) (cp-arg 6) (cp-arg 7) (cp-int (cp-arg 8)) (cp-arg 9) (cp-arg 10) (cp-arg 11) (cp-arg 12)) x (cp-say v)] (if (= (string-index-of v "archive-restore/restored ") 0) 0 1))
        :else (checkpoint-main))))\n'''
(p/'fixture.kotoba').write_text(s+g+main)
