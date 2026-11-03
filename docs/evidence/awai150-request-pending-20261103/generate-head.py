from pathlib import Path
s=Path('/Users/junkawasaki/github/workspaces/codex/awai150-inference-replay-guard/kotoba/profile_cron_bridge.kotoba').read_text();a=s.index('(defn- br-head-problem ');b=s.index('\n(defn- br-slot',a);body=s[a:b]
header=r'''(ns head-fixture (:export [main]))
(defn- cp-bytes [s :string] :vector-i64 (vector-i64-from-bytes (string-to-utf8 s)))
(defn- cp-text [v :vector-i64] :string (string-from-utf8 (bytes-from-vector-i64 v)))
(defn- cp-str [v :vector-i64 i :i64 key :string] :string (if (= key "request") (cp-text v) (if (= key "archiveAck") "/ack" "cid")))
(defn- cp-fs [op :string path :string tail :string] :string
  (if (= path "/error/request.tmp") "unavailable"
    (let [r (typed-cap-call 35 :string :string (str "\u0000FS2\u0000" op path "\u0000" tail))]
      (if (and (>= (string-byte-length r) 5) (= (string-substring r 0 5) "\u0000FS2\u0000")) (string-substring r 5 (string-length r)) ""))))
(defn- json-valid? [v :vector-i64] :bool true)
(defn- json-canonical? [v :vector-i64] :bool true)
(defn- rt-admitted-head? [cfg :vector-i64 head :vector-i64] :bool true)
(defn- cp-exists? [p :string] :bool true)
(defn- cp-read [p :string] :vector-i64 (cp-bytes "ack"))
(defn- rt-archive-ack? [p :string cid :string] :bool true)
'''
main='''\n(defn main [] :i64
 (let [path (typed-cap-call 38 :string :string "0")
       result (br-head-problem (cp-bytes path) (cp-bytes "head"))
       out (typed-cap-call 37 :string :string (str result "\\n"))] 0))\n'''
q=Path('/private/tmp/awai150-request-unknown');(q/'head.kotoba').write_text(header+body+main)
guard='''        (not (= (cp-fs "D" (str (cp-str cfg 0 "request") ".tmp") "") "0")) "bridge/request-unknown"
''';assert body.count(guard)==1;(q/'head-old.kotoba').write_text(header+body.replace(guard,'')+main)
