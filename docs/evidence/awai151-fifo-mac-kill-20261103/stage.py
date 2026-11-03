# Test-only: generate write-boundary pauses for the diagnostic Mac runtime.
import pathlib,subprocess,sys
source,out=map(pathlib.Path,sys.argv[1:]); linux_stage=pathlib.Path(__file__).parent.parent/'awai151-fifo-portable-20261103'/'stage.py'
subprocess.run([sys.executable,str(linux_stage),str(source),str(out)],check=True)
old='(let [sent (typed-cap-call 39 :string :string "PAUSED\\n") wait (typed-cap-call 7 :string :string "sleep 30000")] true)'
helper=''';; Test-only: block on a full stdout pipe once the write boundary is reached.
(defn- rq-test-pause [] :bool
  (let [v (vector-alloc 262144) s (string-from-utf8 (bytes-from-vector-i64 v))
        sent (typed-cap-call 37 :string :string s)] true))
'''
for x in out.glob('after-*.kotoba'):
 s=x.read_text();assert s.count(old)==1
 x.write_text(s.replace('(defn- rq-commit ',helper+'(defn- rq-commit ').replace(old,'(rq-test-pause)'))
