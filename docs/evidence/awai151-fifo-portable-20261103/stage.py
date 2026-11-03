# Test-only source-unity wrapper and process-kill instrumentation.
import hashlib,pathlib,sys
source,out=map(pathlib.Path,sys.argv[1:]); s=source.read_text()
assert hashlib.sha256(s.encode()).hexdigest()=='efd391789af509b94eadc65a35d1676ef2e6fbd2a8186b3f75cd7ea48ea97619'
s=s.replace('(ns murakumo.resource-fifo (:export [resource-fifo-main]))','(ns fifo-gate (:export [main]))')+'\n(defn main [] :i64 (resource-fifo-main))\n'
pause='(let [sent (typed-cap-call 39 :string :string "PAUSED\\n") wait (typed-cap-call 7 :string :string "sleep 30000")] true)'
targets={
 'after-intent':('(= (rq-fs "M" root "") "1") (rq-write? tmp next)','(= (rq-fs "M" root "") "1") '+pause+' (rq-write? tmp next)'),
 'after-pending':('(= (rq-fs "M" tmp "") "1") (= (rq-fs "A" tmp "") next)','(= (rq-fs "M" tmp "") "1") '+pause+' (= (rq-fs "A" tmp "") next)'),
 'after-rename':('(= (rq-fs "L" tmp state) "1")\n         (= (rq-fs "M" root "") "1")','(= (rq-fs "L" tmp state) "1") '+pause+'\n         (= (rq-fs "M" root "") "1")'),
 'after-sync':('(= (rq-fs "M" root "") "1") (= (rq-fs "L" intent (str root "/queue.last")) "1")','(= (rq-fs "M" root "") "1") '+pause+' (= (rq-fs "L" intent (str root "/queue.last")) "1")'),
 'after-archive':('(= (rq-fs "L" intent (str root "/queue.last")) "1")\n         (= (rq-fs "M" root "") "1")','(= (rq-fs "L" intent (str root "/queue.last")) "1") '+pause+'\n         (= (rq-fs "M" root "") "1")')}
with (out/'fifo.kotoba').open('x') as f:f.write(s)
for n,(a,b) in targets.items():
 assert s.count(a)==1,n
 with (out/(n+'.kotoba')).open('x') as f:f.write(s.replace(a,b))
