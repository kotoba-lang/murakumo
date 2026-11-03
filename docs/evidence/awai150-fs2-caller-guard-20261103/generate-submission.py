from pathlib import Path
p=Path('/private/tmp/awai150-fs2-caller-guard');exec((p/'generate.py').read_text().split('header=r')[0])
header=r'''(ns submission-guard (:export [main]))
(defn- cp-bytes [s :string] :vector-i64 (vector-i64-from-bytes (string-to-utf8 s)))
(defn- cp-fs [op :string path :string tail :string] :string
 (let [r (typed-cap-call 35 :string :string (str "\u0000FS2\u0000" op path "\u0000" tail))]
  (if (and (>= (string-byte-length r) 5) (= (string-substring r 0 5) "\u0000FS2\u0000")) (string-substring r 5 (string-length r)) "")))
(defn- cp-exists? [p :string] :bool (= (cp-fs "D" p "") "1"))
(defn- cp-read [p :string] :vector-i64 (cp-bytes "data"))
(defn- cp-cid [v :vector-i64] :string "cid")
(defn- pr-unit-active? [s :string] :bool false)
(defn- pr-unit-state [s :string] :string "unit/not-found")
'''
main='''(defn main [] :i64
 (let [root (typed-cap-call 38 :string :string "0") result (probe root)
       sent (typed-cap-call 37 :string :string (str result "\\n"))] 0))
'''
for label,old in [('submission-current',False),('submission-previous',True)]:
 s=load('kotoba/profile_recovery.kotoba',old);start=s.index('                    '+('present (cp-exists? request)' if old else 'request-status (cp-fs "D" request'))
 end=s.index('\n                    submitted (cond',start);bindings=s[start:end]
 probe='''(defn- probe [root :string] :string
 (let [request (str root "/request") marker (str root "/marker") response (str root "/response") unit "unit" body-bytes (cp-bytes "data")
'''+bindings+'''] decision))\n'''
 (p/(label+'.kotoba')).write_text(header+extract(s,'pr-run-decision')+probe+main)
