# Test-only instrumentation: inject an observable pause at each write boundary.
import hashlib, pathlib, sys
source, out = map(pathlib.Path, sys.argv[1:])
s=source.read_text()
assert hashlib.sha256(s.encode()).hexdigest() == 'dd220f506002f5fac64067f8312a438990200adc6ae7bb6d8ae8cadc7072392d'
s=s.replace('(ns murakumo.resource-fifo (:export [resource-fifo-main]))','(ns fifo-gate (:export [main]))')+'\n(defn main [] :i64 (resource-fifo-main))\n'
pause='(let [sent (typed-cap-call 39 :string :string "PAUSED\\n") wait (typed-cap-call 7 :string :string "sleep 30000")] true)'
targets={
 'after-intent': ('(= (rq-fs "M" root "") "1") (= (rq-fs "B" tmp next) next)', '(= (rq-fs "M" root "") "1") '+pause+' (= (rq-fs "B" tmp next) next)'),
 'after-pending': ('(= (rq-fs "M" tmp "") "1") (= (rq-fs "A" tmp "") next)', '(= (rq-fs "M" tmp "") "1") '+pause+' (= (rq-fs "A" tmp "") next)'),
 'after-rename': ('(= (rq-fs "L" tmp state) "1")\n         (= (rq-fs "M" root "") "1")', '(= (rq-fs "L" tmp state) "1") '+pause+'\n         (= (rq-fs "M" root "") "1")'),
 'after-sync': ('(= (rq-fs "M" root "") "1") (= (rq-fs "J" intent "") "1")', '(= (rq-fs "M" root "") "1") '+pause+' (= (rq-fs "J" intent "") "1")'),
 'after-unlink': ('(= (rq-fs "J" intent "") "1")\n         (= (rq-fs "M" root "") "1")', '(= (rq-fs "J" intent "") "1") '+pause+'\n         (= (rq-fs "M" root "") "1")')}
for name,(old,new) in targets.items():
 assert s.count(old)==1
 path=out/(name+'.kotoba')
 with path.open('x') as f: f.write(s.replace(old,new))
