#!/usr/bin/env python3
"""Read-only local diagnostic of the actual consumer and shipped stale KIR.

Explicit Node/nbb and Kotoba sources are diagnostic inputs, not product
runtime dependencies. No device probe, inference or deployment is performed.
"""
import argparse
import hashlib
from pathlib import Path
import resource
import subprocess

ROOT = Path(__file__).resolve().parents[2]
KIR = ROOT / 'resources/murakumo/oracle/accelerator_budget_core.kir.edn'
CONSUMER = ROOT / 'src/murakumo/fleet/accelerator.cljk'
parser = argparse.ArgumentParser(description=__doc__)
for name in ('node', 'nbb-cli', 'kotoba-root'):
    parser.add_argument('--' + name, type=Path, required=True)
parser.add_argument('--kir-sha256', required=True)
a = parser.parse_args()
node, nbb, kotoba = a.node.resolve(strict=True), a.nbb_cli.resolve(strict=True), a.kotoba_root.resolve(strict=True)
pin = hashlib.sha256(KIR.read_bytes()).hexdigest()
consumer_pin = hashlib.sha256(CONSUMER.read_bytes()).hexdigest()
if pin != a.kir_sha256:
    raise ValueError('KIR digest mismatch')
libs = ('kotoba.osaho', 'kotoba.edn', 'kotoba.text', 'kotoba.bytes',
        'kotoba.kotobaHir', 'kotoba.coll', 'gov.nist.sha2')
classpath = ':'.join([str(ROOT / 'src')] + [str(kotoba / 'lib' / name / 'src') for name in libs])
# JSON's string escaping is valid for these ClojureScript path literals.
import json
resource_dir = json.dumps(str(ROOT / 'resources') + '/')
program = '''(require (quote [murakumo.fleet.accelerator :as a]) (quote [murakumo.kotoba.oracle :as o]))
(o/set-resource-loader! (fn [p] (.readFileSync (js/require "fs") (str RESOURCE p) "utf8")))
(assert (= 100 (a/aperture-bytes {:vram-total 100 :gtt-total 0})))
(assert (= 120 (a/headroom-default-milli)))
(assert (= 88 (a/usable-bytes {:vram-total 100 :gtt-total 0} 120)))
(assert (= -1 (a/aperture-bytes {:vram-total 9007199254740991 :gtt-total 1})))
(assert (= -1 (a/usable-bytes {:vram-total -1 :gtt-total 0} 0)))
(let [d (a/admit {:vram-total 100 :gtt-total 0 :claims [] :unreadable-claims 0} {} :resident 1 0)]
  (assert (false? (:admitted? d))) (assert (= :oracle-unqualified (:reason d)))
  (assert (= 0 (:granted d))) (println "PASS actual consumer and pinned stale KIR refuse"))
'''.replace('RESOURCE', resource_dir)
def limit():
    resource.setrlimit(resource.RLIMIT_CPU, (3, 3))
r = subprocess.run([str(node), str(nbb), '-cp', classpath, '-e', program], cwd='/private/tmp',
                   capture_output=True, text=True, timeout=15, preexec_fn=limit)
if r.returncode or r.stdout != 'PASS actual consumer and pinned stale KIR refuse\n' or r.stderr:
    raise RuntimeError((r.returncode, r.stdout[-500:], r.stderr[-1000:]))
if hashlib.sha256(KIR.read_bytes()).hexdigest() != pin:
    raise RuntimeError('KIR changed during diagnostic')
if hashlib.sha256(CONSUMER.read_bytes()).hexdigest() != consumer_pin:
    raise RuntimeError('consumer changed during diagnostic')
print(r.stdout.strip(), 'kir-sha256', pin, 'consumer-sha256', consumer_pin)
