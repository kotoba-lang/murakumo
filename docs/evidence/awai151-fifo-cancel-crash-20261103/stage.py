# Test-only source-unity wrapper and process-kill instrumentation.
import hashlib,pathlib,sys
source,out=map(pathlib.Path,sys.argv[1:]); s=source.read_text()
assert hashlib.sha256(s.encode()).hexdigest()=='ce6feba9274c30331a962c0a97ef2ed9e32d4eade0527de9e63a09e752e6a598'
s=s.replace('(ns murakumo.resource-fifo (:export [resource-fifo-main]))','(ns fifo-gate (:export [main]))')+'\n(defn main [] :i64 (resource-fifo-main))\n'
pause='(rq-test-pause)'
targets={
 'after-intent':('(= (rq-fs "M" root "") "1") (rq-write? tmp next)','(= (rq-fs "M" root "") "1") '+pause+' (rq-write? tmp next)'),
 'after-pending':('(= (rq-fs "M" tmp "") "1") (= (rq-fs "A" tmp "") next)','(= (rq-fs "M" tmp "") "1") '+pause+' (= (rq-fs "A" tmp "") next)'),
 'after-rename':('(= (rq-fs "L" tmp state) "1")\n         (= (rq-fs "M" root "") "1")','(= (rq-fs "L" tmp state) "1") '+pause+'\n         (= (rq-fs "M" root "") "1")'),
 'after-sync':('(= (rq-fs "M" root "") "1") (= (rq-fs "L" intent (str root "/queue.last")) "1")','(= (rq-fs "M" root "") "1") '+pause+' (= (rq-fs "L" intent (str root "/queue.last")) "1")'),
 'after-archive':('(= (rq-fs "L" intent (str root "/queue.last")) "1")\n         (= (rq-fs "M" root "") "1")','(= (rq-fs "L" intent (str root "/queue.last")) "1") '+pause+'\n         (= (rq-fs "M" root "") "1")')}
with (out/'fifo.kotoba').open('x') as f:f.write(s)
helper='''(defn- rq-test-pause [] :bool
  (let [v (vector-alloc 262144) s (string-from-utf8 (bytes-from-vector-i64 v)) sent (typed-cap-call 37 :string :string s)] true))
'''
for n,(a,b) in targets.items():
 assert s.count(a)==1,n
 with (out/(n+'.kotoba')).open('x') as f:f.write(s.replace('(defn- rq-commit ',helper+'(defn- rq-commit ').replace(a,b))
