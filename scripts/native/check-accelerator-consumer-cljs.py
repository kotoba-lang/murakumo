#!/usr/bin/env python3
"""Explicit local ClojureScript source diagnostic; not a native governor test."""

import argparse
import hashlib
import json
from pathlib import Path
import resource
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'src/murakumo/fleet/accelerator.cljk'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--node', required=True, type=Path)
    parser.add_argument('--nbb-cli', required=True, type=Path)
    args = parser.parse_args()
    node, nbb = args.node.resolve(strict=True), args.nbb_cli.resolve(strict=True)
    raw = SOURCE.read_bytes()
    source = raw.decode('utf-8')

    def section(start, end):
        if source.count(start) != 1 or source.count(end) != 1:
            raise ValueError('source anchors changed')
        return source[source.index(start):source.index(end)]

    prelude = '''(ns awai151-probe (:require [clojure.string :as str]))
(def unmeasured -1)
(def classes {:resident 0 :opportunistic 1})
(def oracle-usable (atom 100))
(defn o [op args] (case op "aperture-bytes" (apply + args) "headroom-default-milli" 0
                       "usable-bytes" @oracle-usable "admit-code" (if (= args [0 76 100 20 5]) 3 0)
                       "granted-bytes" (if (= args [0 100 20 5]) 75 100) "code-name" "admitted"))
'''
    cases = r'''
(let [p (parse-probe "vram-total\t100\ngtt-total\t0\nclaim\t1\t42garbage\tw\nclaim\t2\t9007199254740992\tw\nclaim\t3\t-1\tw\n")
      good {:vram-total 100 :gtt-total 0 :claims [] :unreadable-claims 0}
      bad [(dissoc good :claims) (assoc good :claims [42])
           (assoc good :claims [{:pid "1" :comm "w" :bytes -1}])
           (assoc good :claims [{:pid "1" :comm "w" :bytes 9007199254740991} {:pid "2" :comm "w" :bytes 1}]) p]]
  (assert (= 3 (:unreadable-claims p)))
  (assert (= 9007199254740991 (aperture-bytes {:vram-total 9007199254740990 :gtt-total 1})))
  (doseq [x [{:vram-total 9007199254740991 :gtt-total 1} {:vram-total 1.5 :gtt-total 0}
             {:vram-total "1" :gtt-total 0} {:vram-total 0}]]
    (assert (= -1 (aperture-bytes x))))
  (assert (:admitted? (admit good {} :resident 1 1)))
  ;; The deliberately fail-open synthetic oracle returns usable 100 even for
  ;; unknown inputs; the host must refuse before it can produce a grant.
  (let [d (admit (assoc good :vram-total 9007199254740991 :gtt-total 1) {} :resident 1 0)]
    (assert (= :unmeasured (:reason d))) (assert (false? (:admitted? d)))
    (assert (= 0 (:granted d))))
  (doseq [bad-value [-1 1.5 9007199254740992 nil "100"]]
    (reset! oracle-usable bad-value)
    (let [d (admit good {} :resident 1 0)]
      (assert (= :unmeasured (:reason d))) (assert (false? (:admitted? d)))
      (assert (= 0 (:granted d)))))
  (reset! oracle-usable 100)
  (doseq [x bad] (let [d (admit x {} :resident 1 1)]
                   (assert (= :invalid-observation (:reason d))) (assert (= 0 (:granted d)))))
  (assert (= {:resident 0 :opportunistic 80}
             (committed {:claims [{:pid "9" :comm "w" :bytes 80}]} {"9" :unknown})))
  (println "PASS selected ClojureScript admission diagnostic"))
'''
    program = (prelude + section('(defn- parse-long*', ';; ── budget')
               + section('(defn aperture-bytes', '(defn committed')
               + section('(defn committed', ';; ── demand') + cases)

    def limit():
        resource.setrlimit(resource.RLIMIT_CPU, (3, 3))

    result = subprocess.run([str(node), str(nbb), '-e', program], cwd='/private/tmp',
                            capture_output=True, text=True, timeout=15, preexec_fn=limit)
    expected = 'PASS selected ClojureScript admission diagnostic\n'
    if result.returncode != 0 or result.stdout != expected or result.stderr:
        raise RuntimeError((result.returncode, result.stdout[-1000:], result.stderr[-1000:]))
    guard = '(and valid-claims? (integer? unreadable)'
    if program.count(guard) != 1:
        raise ValueError('observation guard mutation anchor changed')
    mutant = program.replace(guard, '(and true (integer? unreadable)', 1)
    control = subprocess.run([str(node), str(nbb), '-e', mutant], cwd='/private/tmp',
                             capture_output=True, text=True, timeout=15, preexec_fn=limit)
    if control.returncode == 0 or 'Assert failed' not in control.stderr:
        raise RuntimeError(('guard-removal control not detected', control.returncode,
                            control.stdout[-1000:], control.stderr[-1000:]))
    total_guard = '(<= vram-total (- 9007199254740991 gtt-total))'
    if program.count(total_guard) != 1:
        raise ValueError('aperture total mutation anchor changed')
    mutant = program.replace(total_guard, 'true', 1)
    control = subprocess.run([str(node), str(nbb), '-e', mutant], cwd='/private/tmp',
                             capture_output=True, text=True, timeout=15, preexec_fn=limit)
    if control.returncode == 0 or 'Assert failed' not in control.stderr:
        raise RuntimeError(('aperture guard-removal control not detected', control.returncode,
                            control.stdout[-1000:], control.stderr[-1000:]))
    propagation_guard = '(and (integer? aperture) (<= 0 aperture 9007199254740991))'
    if program.count(propagation_guard) != 1:
        raise ValueError('unknown propagation mutation anchor changed')
    mutant = program.replace(propagation_guard, 'true', 1)
    control = subprocess.run([str(node), str(nbb), '-e', mutant], cwd='/private/tmp',
                             capture_output=True, text=True, timeout=15, preexec_fn=limit)
    if control.returncode == 0 or 'Assert failed' not in control.stderr:
        raise RuntimeError(('unknown propagation control not detected', control.returncode,
                            control.stdout[-1000:], control.stderr[-1000:]))
    measurement_guard = 'measured? (and (integer? usable) (<= 0 usable 9007199254740991))'
    if program.count(measurement_guard) != 1:
        raise ValueError('usable measurement mutation anchor changed')
    mutant = program.replace(measurement_guard, 'measured? true', 1)
    control = subprocess.run([str(node), str(nbb), '-e', mutant], cwd='/private/tmp',
                             capture_output=True, text=True, timeout=15, preexec_fn=limit)
    if control.returncode == 0 or 'Assert failed' not in control.stderr:
        raise RuntimeError(('usable guard-removal control not detected', control.returncode,
                            control.stdout[-1000:], control.stderr[-1000:]))
    if SOURCE.read_bytes() != raw:
        raise RuntimeError('source changed during diagnostic')
    print(json.dumps({'sourceSha256': hashlib.sha256(raw).hexdigest(),
                      'programSha256': hashlib.sha256(program.encode()).hexdigest(),
                      'nodeSha256': hashlib.sha256(node.read_bytes()).hexdigest(),
                      'nbbCliSha256': hashlib.sha256(nbb.read_bytes()).hexdigest(),
                      'result': 'pass', 'guardRemovalControl': 'detected',
                      'scope': 'selected source; synthetic oracle and capacity'}))


if __name__ == '__main__':
    main()
