from pathlib import Path
import subprocess
repo=Path('/Users/junkawasaki/github/workspaces/codex/awai150-inference-replay-guard'); path='kotoba/profile_cron_bridge.kotoba'
current=(repo/path).read_text(); prior=subprocess.check_output(['git','show','HEAD:'+path],cwd=repo,text=True)
def body(s):
 a=s.index('(defn- br-install ');b=s.index('\n(defn- br-completed?',a);return s[a:b]
header=r'''(ns request-read-fixture (:export [main]))
(defn- cp-bytes [s :string] :vector-i64 (vector-i64-from-bytes (string-to-utf8 s)))
(defn- cp-text [v :vector-i64] :string (string-from-utf8 (bytes-from-vector-i64 v)))
(defn- cp-str [v :vector-i64 i :i64 key :string] :string (cp-text v))
(defn- cp-cid [v :vector-i64] :string (cp-text v))
(defn- json-valid? [v :vector-i64] :bool (not (= (cp-text v) "bad")))
(defn- json-canonical? [v :vector-i64] :bool true)
(defn- json-kind [v :vector-i64 i :i64] :i64 1)
(defn- cp-fs [op :string path :string tail :string] :string
  (if (and (= op "D") (= path "/status-unknown/request")) "unavailable"
    (let [r (typed-cap-call 35 :string :string (str "\u0000FS2\u0000" op path "\u0000" tail))]
      (if (and (>= (string-byte-length r) 5) (= (string-substring r 0 5) "\u0000FS2\u0000")) (string-substring r 5 (string-length r)) ""))))
(defn- cp-exists? [p :string] :bool (= (cp-fs "D" p "") "1"))
(defn- cp-read [p :string] :vector-i64 (if (= p "/read-error/request") (vector-alloc 0) (cp-bytes (cp-fs "A" p ""))))
(defn- rt-save [p :string b :vector-i64] :bool true)
'''
main='''\n(defn main [] :i64
 (let [path (typed-cap-call 38 :string :string "0") head (typed-cap-call 38 :string :string "1")
       result (br-install (cp-bytes path) (cp-bytes head) (cp-bytes "wanted"))
       out (typed-cap-call 37 :string :string (str result "\\n"))] 0))\n'''
p=Path('/private/tmp/awai150-request-read-unknown');(p/'current.kotoba').write_text(header+body(current)+main);(p/'previous.kotoba').write_text(header+body(prior)+main)
