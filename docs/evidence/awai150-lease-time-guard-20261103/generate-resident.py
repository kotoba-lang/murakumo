# Test-only fixture: extract actual resident functions, substitute quorum and IO.
from pathlib import Path
p=Path('/private/tmp/awai150-lease-time-guard');s=Path('kotoba/profile_resident.kotoba').read_text()
a=s.index('(defn- rt-lease-bound?');b=s.index('\n(defn- rt-pins ',a);resident=s[a:b]
core=(p/'fixture.kotoba').read_text();core=core[:core.rindex('(defn main []')]
mock=r'''(defn- cp-bytes [s :string] :vector-i64 (vector-i64-from-bytes (string-to-utf8 s)))
(defn- cp-text [b :vector-i64] :string (string-from-utf8 (bytes-from-vector-i64 b)))
(defn- cp-str [b :vector-i64 i :i64 k :string] :string
 (cond (= k "leaseFile") "/lease" (= k "task") "task" (= k "class") "task"
       (= k "node") (if (or (= (cp-text b) "bad") (= (cp-text b) "expired")) "other" "node") :else ""))
(defn- cp-num [b :vector-i64 i :i64 k :string] :i64
 (cond (= k "slot") 0 (= k "epoch") (if (= (cp-text b) "good") 2 1)
       (= k "until") (cond (= (cp-text b) "bad") -1 (= (cp-text b) "expired") 1 :else 200000) :else 0))
(defn- cp-fs [a :string b :string c :string] :string "0")
(defn- cp-read [a :string] :vector-i64 (vector-alloc 0))
(defn- json-valid? [a :vector-i64] :bool true)
(defn- json-canonical? [a :vector-i64] :bool true)
(defn- json-count [a :vector-i64 b :i64] :i64 6)
(defn- json-kind [a :vector-i64 b :i64] :i64 1)
(defn- rt-now [] :i64 100000)
(defn- rt-lease [cfg :vector-i64 e :i64] :vector-i64
 (cp-bytes (if (= e 1) (if (= (cp-text cfg) "bad") "bad" "expired") "good")))
(defn- rt-save [p :string b :vector-i64] :bool true)
(defn- recovery-lease-problem [a :vector-i64 b :string c :string d :i64 e :i64] :string "recovery/lease-admitted")
'''
main='''(defn main [] :i64
 (if (and (= (vector-count (rt-current-lease (cp-bytes "bad") 1)) 0)
          (= (cp-text (rt-current-lease (cp-bytes "expired") 1)) "good")) 0 1))\n'''
src=core+mock+resident+main;(p/'resident.kotoba').write_text(src)
needle='(not (rt-lease-bound? cfg learned e now))';assert src.count(needle)==1
(p/'resident-mutant.kotoba').write_text(src.replace(needle,'false'))
