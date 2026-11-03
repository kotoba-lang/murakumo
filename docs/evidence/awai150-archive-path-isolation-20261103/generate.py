from pathlib import Path
repo=Path('/Users/junkawasaki/github/workspaces/codex/awai150-inference-replay-guard')
base=Path('/private/tmp/awai150-archive-paths')
cp=(repo/'kotoba/git_checkpoint.kotoba').read_text(); ga=(repo/'kotoba/github_checkpoint_archive.kotoba').read_text(); en=(repo/'kotoba/fleet_node_enrollment.kotoba').read_text()
def section(s,start,end):
 assert s.count(start)==1,(start,s.count(start));return s[s.index(start):s.index(end,s.index(start))]
field=section(en,'(defn field-problem','\n(defn declaration-text')
safe=section(cp,'(defn- cp-safe-absolute?','\n;; Intentionally')
overlap=section(ga,'(defn- ga-path-overlap?','\n;; Never copy')
# There are two distinct conds; extract each by their enclosing definition.
def cond_of(start,end,label):
 text=section(ga,start,end);return text[text.index('  (cond '):text.index(' "'+label+'"')]
a=cond_of('(defn- ga-archive [','\n(defn github-checkpoint-archive-main','archive/input-refused')
r=cond_of('(defn- ga-restore [','\n(defn github-checkpoint-restore-main','archive-restore/input-refused')
header='''(ns paths (:export [main]))
(defn- en-bytes [s :string] :vector-i64 (vector-i64-from-bytes (string-to-utf8 s)))
'''+field+safe+overlap+'''
(defn- ga-repo? [s :string] :bool true)
(defn- cp-raw? [s :string] :bool true)
(defn- ga-verified? [a :string b :string c :string d :string e :string f :string g :i64 h :string i :string] :bool true)
(defn- cp-exists? [s :string] :bool false)
(defn- archive [work :string store :string] :bool
 (let [gh "/bin/gh" repo "x/y" cid "c" author "a" profile "p" policy "p" environment "e" epoch 1 parent "p" task "t"]
'''+a+''' true :else false)))
(defn- restore [work :string dest :string] :bool
 (let [gh "/bin/gh" repo "x/y" cid "c"]
'''+r+''' true :else false)))
'''
checks='''(defn main [] :i64
 (if (and (archive "" "/work") (archive "/" "/work") (archive "/work" "/work") (archive "/work" "/work/sub") (archive "/work/sub" "/work")
          (restore "/work" "/work/sub") (restore "/work/sub" "/work")
          (archive "/work/" "/work/sub") (archive "/work/./sub" "/work")
          (archive "/work//sub" "/work") (restore "/work/." "/work")
          (not (archive "/work" "/work-a")) (not (restore "/work" "/work-a"))
          (not (archive "/space/path" "/space/pathology"))) 0 1))
'''
(base/'fixture.kotoba').write_text(header+checks)
mutant=(header+checks).replace('(ga-path-overlap? work store)','(= work store)').replace('(ga-path-overlap? work dest)','(= work dest)')
(base/'mutant.kotoba').write_text(mutant)
alias_clause='''
       (< (string-index-of s "//") 0) (< (string-index-of s "/./") 0)
       (not (= (string-substring s (- (string-length s) 1) (string-length s)) "/"))
       (not (= (string-substring s (- (string-length s) 2) (string-length s)) "/."))'''
assert (header+checks).count(alias_clause)==1
(base/'alias-mutant.kotoba').write_text((header+checks).replace(alias_clause,''))
