# Test-only: bind the actual request and head code to a local fake transport.
from pathlib import Path
p=Path('/private/tmp/awai150-head-graphql');s=Path('/private/tmp/awai150-head-restore/fixture.kotoba').read_text().replace('/private/tmp/awai150-head-restore',str(p));i=s.rindex('(defn main []');s=s[:i]
old='(= (string-index-of s "/usr/bin/gitARG_SEP") 0)';assert s.count(old)==1;s=s.replace(old,'(or (= (string-index-of s "/usr/bin/gitARG_SEP") 0) (= (string-index-of s "/usr/bin/python3ARG_SEP") 0))')
old='(hc-spawn 0 root capture "/usr/bin/git" count argv)';assert s.count(old)==1;s=s.replace(old,'(hc-spawn 0 root capture (if (= (string-index-of s "/usr/bin/python3ARG_SEP") 0) "/usr/bin/python3" "/usr/bin/git") count argv)')
h=Path('kotoba/github_checkpoint_head_graphql.kotoba').read_text();h=h[h.index('(defn- hg-base64'):];old='(str "https://github.com/" repo ".git")';assert h.count(old)==1;h=h.replace(old,'"file:///private/tmp/awai150-head-graphql/fixture5/remote.git"')
old='(cp-spawn (str gh (cp-a) "api" (cp-a) "--hostname" (cp-a) "github.com" (cp-a) "graphql" (cp-a) "--input" (cp-a) request))';assert h.count(old)==1
h=h.replace(old,'(cp-spawn (str "/usr/bin/python3" (cp-a) "/private/tmp/awai150-head-graphql/fixture5/fake.py" (cp-a) request))')
main='''(defn main [] :i64 (if (= (cp-arg 0) "cas")
 (let [v (hg-advance (cp-arg 1) (cp-arg 2) (cp-arg 3) (cp-arg 4) (cp-arg 5) (cp-arg 6) (cp-arg 7) (cp-int (cp-arg 8)) (cp-arg 9) (cp-arg 10) (cp-arg 11) (cp-arg 12) (cp-arg 13)) x (cp-say v)] (if (= (string-index-of v "head-cas/advanced ") 0) 0 1)) (checkpoint-main)))\n'''
(p/'fixture.kotoba').write_text(s+h+main)
