#!/usr/bin/env python3
"""Run five targeted actual host tests using explicit local nbb inputs.

This diagnostic uses test mocks and does not qualify a governor or real probe.
"""
import argparse
import hashlib
from pathlib import Path
import resource
import subprocess

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser(description=__doc__)
for name in ('node', 'nbb-cli', 'kotoba-root'):
    p.add_argument('--' + name, type=Path, required=True)
a = p.parse_args()
node, nbb, kotoba = a.node.resolve(strict=True), a.nbb_cli.resolve(strict=True), a.kotoba_root.resolve(strict=True)
libs = ('kotoba.osaho', 'kotoba.edn', 'kotoba.text', 'kotoba.bytes', 'kotoba.kotobaHir',
        'kotoba.coll', 'gov.nist.sha2', 'kotoba.test', 'kotoba.spec')
classpath = ':'.join([str(ROOT / 'src'), str(ROOT / 'test')] + [str(kotoba / 'lib' / x / 'src') for x in libs])
paths = [ROOT / 'src/murakumo/fleet/accelerator.cljk', ROOT / 'test/murakumo/fleet/accelerator_test.cljk']
pins = [hashlib.sha256(x.read_bytes()).hexdigest() for x in paths]
program = r'''(require (quote [murakumo.fleet.accelerator-test :as t]) (quote [cljs.test :as ct]))
(ct/test-vars [#'t/aperture-rejects-inexact-or-malformed-measurements
               #'t/unknown-aperture-cannot-be-promoted-by-oracle
               #'t/oracle-cannot-invent-aperture-or-usable-capacity
               #'t/oracle-results-cannot-exceed-observed-remaining-capacity
               #'t/bigint-oracle-results-are-normalized-exactly])
(let [r (:report-counters (ct/get-current-env))]
  (assert (= 5 (:test r))) (assert (>= (:pass r) 23))
  (assert (= 0 (:fail r))) (assert (= 0 (:error r)))
  (println "PASS actual host tests" (pr-str r)))'''
def limit():
    resource.setrlimit(resource.RLIMIT_CPU, (3, 3))
r = subprocess.run([str(node), str(nbb), '-cp', classpath, '-e', program], cwd='/private/tmp',
                   capture_output=True, text=True, timeout=15, preexec_fn=limit)
if r.returncode or not r.stdout.startswith('PASS actual host tests ') or r.stderr:
    raise RuntimeError((r.returncode, r.stdout[-1000:], r.stderr[-1000:]))
if [hashlib.sha256(x.read_bytes()).hexdigest() for x in paths] != pins:
    raise RuntimeError('host source or tests changed during diagnostic')
print(r.stdout.strip(), 'source-and-test-sha256', *pins)
