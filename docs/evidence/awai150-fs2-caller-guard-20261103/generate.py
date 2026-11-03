from pathlib import Path
import subprocess
repo=Path('/Users/junkawasaki/github/workspaces/codex/awai150-inference-replay-guard');out=Path('/private/tmp/awai150-fs2-caller-guard')
def load(path,old):return subprocess.check_output(['git','show','c0e1899ff2e70fcbc60f5996196eecb989fe8170:'+path],cwd=repo,text=True) if old else (repo/path).read_text()
def extract(s,name):
 start=s.index('(defn- '+name+' ');i=start;depth=0;string=False;comment=False;escape=False
 while i<len(s):
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
   if depth==0:return s[start:i+1]+'\n'
  i+=1
 raise ValueError(name)
header=r'''(ns caller-guard (:export [main]))
(defn- cp-bytes [s :string] :vector-i64 (vector-i64-from-bytes (string-to-utf8 s)))
(defn- cp-fs [op :string path :string tail :string] :string
 (if (= op "D")
  (let [r (typed-cap-call 35 :string :string (str "\u0000FS2\u0000" op path "\u0000" tail))]
   (if (and (>= (string-byte-length r) 5) (= (string-substring r 0 5) "\u0000FS2\u0000")) (string-substring r 5 (string-length r)) ""))
  "1"))
(defn- cp-exists? [p :string] :bool (= (cp-fs "D" p "") "1"))
(defn- cp-absent? [p :string] :bool (= (cp-fs "D" p "") "0"))
(defn- cp-write [p :string b :vector-i64] :bool true)
(defn- cp-read [p :string] :vector-i64 (cp-bytes "data"))
(defn- cp-load [p :string c :string] :vector-i64 (cp-bytes "data"))
(defn- cp-cid [b :vector-i64] :string "cid")
(defn- cp-block? [p :string c :string] :bool true)
(defn- cp-parent-dir [p :string] :string "/")
'''
names=[('kotoba/git_checkpoint.kotoba','cp-put'),('kotoba/github_checkpoint_archive.kotoba','ga-copy'),('kotoba/profile_resident.kotoba','rt-save'),('kotoba/profile_recovery.kotoba','pr-save-output'),('kotoba/profile_recovery.kotoba','pr-prepare-submit'),('kotoba/profile_cron_controller.kotoba','nc-mark')]
main='''(defn main [] :i64
 (let [op (typed-cap-call 38 :string :string "0") root (typed-cap-call 38 :string :string "1")
       value (cond (= op "put") (cp-put root (cp-bytes "data"))
                   (= op "copy") (if (ga-copy "/source" root "cid") "true" "false")
                   (= op "save") (if (rt-save (str root "/item") (cp-bytes "data")) "true" "false")
                   (= op "output") (if (pr-save-output (str root "/item") (cp-bytes "data") "cid") "true" "false")
                   (= op "prepare") (if (pr-prepare-submit (str root "/item") (str root "/marker") (cp-bytes "data")) "true" "false")
                   (= op "mark") (if (nc-mark (str root "/marker") "job") "true" "false")
                   :else "bad-op")
       sent (typed-cap-call 37 :string :string (str value "\\n"))] 0))
'''
for label,old in [('current',False),('previous',True)]:
 s=header+''.join(extract(load(path,old),name) for path,name in names)+main;(out/(label+'.kotoba')).write_text(s)
