from pathlib import Path
p=Path('/Users/junkawasaki/github/workspaces/codex/awai150-inference-replay-guard/kotoba/profile_cron_bridge.kotoba');s=p.read_text();a=s.index('(defn- br-install ');b=s.index('\n(defn- br-completed?',a);body=s[a:b]
header=r'''(ns request-fixture (:export [main]))
(defn- cp-bytes [s :string] :vector-i64 (vector-i64-from-bytes (string-to-utf8 s)))
(defn- cp-text [v :vector-i64] :string (string-from-utf8 (bytes-from-vector-i64 v)))
(defn- cp-str [v :vector-i64 i :i64 key :string] :string (cp-text v))
(defn- cp-cid [v :vector-i64] :string (cp-text v))
(defn- cp-fs [op :string path :string tail :string] :string
  (if (= path "/error/request.tmp") "unavailable"
    (let [r (typed-cap-call 35 :string :string (str "\u0000FS2\u0000" op path "\u0000" tail))]
      (if (and (>= (string-byte-length r) 5) (= (string-substring r 0 5) "\u0000FS2\u0000")) (string-substring r 5 (string-length r)) ""))))
(defn- cp-exists? [p :string] :bool (= (cp-fs "D" p "") "1"))
(defn- cp-read [p :string] :vector-i64 (cp-bytes (cp-fs "A" p "")))
(defn- rt-save [p :string b :vector-i64] :bool false)
'''
main='''\n(defn main [] :i64
 (let [path (typed-cap-call 38 :string :string "0") head (typed-cap-call 38 :string :string "1")
       result (br-install (cp-bytes path) (cp-bytes head) (cp-bytes "wanted"))
       out (typed-cap-call 37 :string :string (str result "\\n"))] 0))\n'''
q=Path('/private/tmp/awai150-request-unknown');(q/'fixture.kotoba').write_text(header+body+main)
new='''    (cond (not (= (cp-fs "D" (str path ".tmp") "") "0")) "bridge/request-unknown"
          (= consumed wanted) "bridge/already-consumed"
          (and (not (= old "none")) (not (= old consumed)) (not (= old wanted))) "bridge/pending-request"
          (= old wanted) "bridge/request-ready"'''
old='''    (cond (= consumed wanted) "bridge/already-consumed"
          (and (not (= old "none")) (not (= old consumed)) (not (= old wanted))) "bridge/pending-request"
          (= old wanted) "bridge/request-ready"
          (cp-exists? (str path ".tmp")) "bridge/request-unknown"'''
assert body.count(new)==1;(q/'old.kotoba').write_text(header+body.replace(new,old)+main)
