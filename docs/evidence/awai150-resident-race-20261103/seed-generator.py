# Test-only expired-value proposer, built on the prior resident unity fixture.
from pathlib import Path
import sys
src=Path(sys.argv[1]).read_text();i=src.rindex('(defn main []')
main='''(defn main [] :i64 (let [c (cp-read (cp-arg 0)) old (- (ln-now "clock") 120000) r (lease-group-round 1 (cp-str c 0 "node") (lease-text (cp-str c 0 "node") (cp-str c 0 "task") 0 1 old 1) (cp-str c 0 "voters") (lease-token (cp-str c 0 "tokenFile"))) x (cp-say r)] (if (= r "") 1 0)))\n'''
Path(sys.argv[2]).write_text(src[:i]+main)
