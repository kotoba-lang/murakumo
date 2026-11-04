#!/usr/bin/env python3
"""Bounded Darwin diagnostic of the actual deadline arithmetic, not lease fencing."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import resource
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--compiler', type=Path, required=True)
    p.add_argument('--compiler-sha256', required=True)
    p.add_argument('--packager', type=Path, required=True)
    p.add_argument('--packager-sha256', required=True)
    a = p.parse_args()
    compiler, packager = a.compiler.resolve(strict=True), a.packager.resolve(strict=True)
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    if digest(compiler) != a.compiler_sha256 or digest(packager) != a.packager_sha256:
        raise ValueError('diagnostic tool pin mismatch')
    source = (ROOT / 'kotoba/profile_recovery.kotoba').read_text()
    start, end = source.index('(defn- pr-window-seconds '), source.index('(defn- pr-count ')
    window = source[start:end]
    anchor = '(< now started)'
    if window.count(anchor) != 1:
        raise ValueError('clock rollback guard anchor changed')
    cases = '''
(defn main [] :i64
 (if (and (= (pr-window-seconds 100000 110000 100000 10) 10)
          (= (pr-window-seconds 100000 110000 106500 10) 3)
          (= (pr-window-seconds 100000 110000 109001 10) -1)
          (= (pr-window-seconds 100000 110000 110000 10) -1)
          (= (pr-window-seconds 100000 110000 99999 10) -1)
          (= (pr-window-seconds 0 110000 100000 10) -1)
          (= (pr-window-seconds 100000 110000 100000 0) -1)) 0 1))
'''
    count_start = source.index('(defn- pr-count [')
    count_end = source.index('(defn- pr-flint-output-budget ', count_start)
    count_source = source[count_start:count_end]
    count_guard = '(pr-window-seconds started deadline (pr-wall-ms) seconds)'
    if count_source.count(count_guard) != 1 or count_source.count('(not reuse-only)') != 1:
        raise ValueError('count deadline mutation anchor changed')
    count_stubs = '''
(defn- pr-local-flint? [r :vector-i64] :bool true)
(defn- pr-count-id [r :vector-i64 b :vector-i64] :string "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(defn- cp-fs [op :string path :string tail :string] :string "0")
(defn- cp-str [b :vector-i64 i :i64 key :string] :string "value")
(defn- pr-read-bounded [path :string limit :i64] :vector-i64 (vector-alloc 0))
(defn- cp-cid [b :vector-i64] :string "cid")
(defn- cp-text [b :vector-i64] :string "")
(defn- pr-unit-state [unit :string] :string "absent")
(defn- pr-run-decision [state :string present :bool matches :bool marked :bool marker-matches :bool response :bool] :string "profile/new-unit")
(defn- pr-prepare-submit [request :string marker :string body :vector-i64] :bool true)
(defn- pr-wall-ms [] :i64 106500)
(defn- pr-submit-kind [unit :string request :string response :string seconds :i64 runtime :vector-i64 count :bool] :bool
  (let [a (vector-assoc! runtime 0 (+ (vector-at runtime 0) 1))
        b (vector-assoc! runtime 1 seconds)] count))
(defn- pr-count-wait [unit :string response :string started :i64 deadline :i64 seconds :i64] :i64 12)
'''
    count_cases = '''
(defn main [] :i64
 (let [normal (vector-alloc 2) expired (vector-alloc 2) reuse (vector-alloc 2)
       a (pr-count normal "/test" 10 [1] 100000 110000 false)
       b (pr-count expired "/test" 10 [1] 100000 105000 false)
       c (pr-count reuse "/test" 10 [1] 100000 110000 true)]
   (if (and (= a 12) (= (vector-at normal 0) 1) (= (vector-at normal 1) 3)
            (= b -1) (= (vector-at expired 0) 0) (= c -1) (= (vector-at reuse 0) 0)) 0 1)))
'''
    reuse_stub_anchor = ':string "profile/new-unit")'
    reuse_decision = '(= decision "profile/reuse-unit")'
    if count_stubs.count(reuse_stub_anchor) != 1 or count_source.count(reuse_decision) != 1:
        raise ValueError('existing-count control anchor changed')
    reuse_stubs = count_stubs.replace(reuse_stub_anchor, ':string "profile/reuse-unit")', 1)
    reuse_cases = '''
(defn main [] :i64
 (let [runtime (vector-alloc 2)
       result (pr-count runtime "/test" 10 [1] 100000 110000 true)]
  (if (and (= result 12) (= (vector-at runtime 0) 0)) 0 1)))
'''
    wait_start = source.index('(defn- pr-count-wait ')
    wait_end = source.index(';; Flint generation', wait_start)
    count_wait = source[wait_start:wait_end]
    wait_guard = '(< (pr-window-seconds started deadline (pr-wall-ms) seconds) 1)'
    if count_wait.count(wait_guard) != 1:
        raise ValueError('count wait deadline guard anchor changed')
    wait_stubs = r'''
(defn- pr-wall-ms [] :i64 106500)
(defn- pr-unit-code [unit :string] :i64
 (let [created (typed-cap-call 35 :string :string (str "\u0000FS2\u0000Ipoll-seen\u0000"))] 1))
(defn- pr-read-bounded [path :string limit :i64] :vector-i64 [1])
(defn- pr-count-value [b :vector-i64] :i64 12)
(defn- pr-pause [] :bool false)
'''
    wait_cases = r'''
(defn main [] :i64
 (let [expired (pr-count-wait "u" "r" 100000 105000 10)
       before (typed-cap-call 35 :string :string (str "\u0000FS2\u0000Dpoll-seen\u0000"))
       valid (pr-count-wait "u" "r" 100000 110000 10)
       after (typed-cap-call 35 :string :string (str "\u0000FS2\u0000Dpoll-seen\u0000"))]
   (if (and (= expired -1) (= before "\u0000FS2\u00000")
            (= valid 12) (= after "\u0000FS2\u00001")) 0 1)))
'''
    resident = (ROOT / 'kotoba/profile_resident.kotoba').read_text()
    resident_start = resident.index('(defn- rt-dispatch ')
    resident_end = resident.index('(defn- rt-turn ', resident_start)
    dispatch = resident[resident_start:resident_end]
    refresh = 'again (rt-lease cfg (cp-num lease 0 "epoch"))'
    if dispatch.count(refresh) != 1:
        raise ValueError('resident lease refresh mutation anchor changed')
    recheck_guard = '(< (pr-window-seconds now deadline (rt-now) budget) 1)'
    if dispatch.count(recheck_guard) != 1:
        raise ValueError('pre-refresh deadline guard anchor changed')
    resident_stubs = r'''
(defn- rt-lease [cfg :vector-i64 epoch :i64] :vector-i64
  (let [n (+ (vector-at cfg 0) 1) x (vector-assoc! cfg 0 n)]
    (cond (and (= n 2) (= (vector-at cfg 3) 1)) [2]
          (and (= n 2) (= (vector-at cfg 3) 3)) [3] :else [1])))
(defn- rt-now [] :i64
 (if (= (typed-cap-call 35 :string :string (str "\u0000FS2\u0000Dstep-time\u0000")) "\u0000FS2\u00001") 116500 106500))
(defn- rt-post-problem [cfg :vector-i64 head :vector-i64 lease :vector-i64 fresh :vector-i64 now :i64] :string
  (cond (= (vector-at fresh 0) 1) "resident/admission-held"
        (= (vector-at fresh 0) 3) "resident/retention-expired-proposal-retained"
        :else "resident/lease-lost"))
(defn- rf-budget [a :i64 b :i64 c :i64] :i64 10)
(defn- rt-clock-window? [now :i64 margin :i64] :bool true)
(defn- rt-runtime [cfg :vector-i64] :vector-i64 cfg)
(defn- cp-num [b :vector-i64 i :i64 key :string] :i64 1)
(defn- cp-str [b :vector-i64 i :i64 key :string] :string "/cache")
(defn- pr-local-flint? [b :vector-i64] :bool true)
(defn- pr-step-resident [repo :string runtime :vector-i64 cache :string next :i64 seconds :i64 start :i64 deadline :i64 phase :i64] :string
  (if (= phase 1)
    (let [x (vector-assoc! runtime 1 (+ (vector-at runtime 1) 1))]
      (if (= (vector-at runtime 3) 2) "profile/context-budget-unknown-or-insufficient"
        (if (= (vector-at runtime 3) 4)
          (let [created (typed-cap-call 35 :string :string (str "\u0000FS2\u0000Istep-time\u0000"))] "profile/flint-count-ready")
          "profile/flint-count-ready")))
    (let [x (vector-assoc! runtime 2 (+ (vector-at runtime 2) 1))] "profile/step-completed")))
'''
    resident_cases = '''
(defn main [] :i64
 (let [good (vector-alloc 4) changed (vector-alloc 4) unavailable (vector-alloc 4) expired (vector-alloc 4) timed (vector-alloc 4)
       x (vector-assoc! changed 3 1) y (vector-assoc! unavailable 3 2) z (vector-assoc! expired 3 3) t (vector-assoc! timed 3 4)
       a (rt-dispatch good [1] [1] "/test" 1)
       b (rt-dispatch changed [1] [1] "/test" 1)
       c (rt-dispatch unavailable [1] [1] "/test" 1)
       d (rt-dispatch expired [1] [1] "/test" 1)
       e (rt-dispatch timed [1] [1] "/test" 1)]
  (if (and (= a "profile/step-completed") (= (vector-at good 0) 2)
           (= (vector-at good 1) 1) (= (vector-at good 2) 1)
           (= b "resident/lease-lost") (= (vector-at changed 0) 2)
           (= (vector-at changed 1) 1) (= (vector-at changed 2) 0)
           (= c "profile/context-budget-unknown-or-insufficient") (= (vector-at unavailable 0) 1)
           (= (vector-at unavailable 1) 1) (= (vector-at unavailable 2) 0)
           (= d "resident/retention-expired-proposal-retained") (= (vector-at expired 0) 2)
           (= (vector-at expired 1) 1) (= (vector-at expired 2) 0)
           (= e "profile/budget-refused") (= (vector-at timed 0) 1)
           (= (vector-at timed 1) 1) (= (vector-at timed 2) 0)) 0 1)))
'''
    def limit():
        resource.setrlimit(resource.RLIMIT_CPU, (3, 3))
    with tempfile.TemporaryDirectory(prefix='awai-flint-window-', dir='/private/tmp') as tmp:
        root = Path(tmp)
        fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
        saved = None
        if fd != 3:
            try: saved = os.dup(3)
            except OSError: pass
            os.dup2(fd, 3)
        def run(argv, expected):
            result = subprocess.run([str(x) for x in argv], cwd=root, env={'PATH':'/nonexistent'},
                                    pass_fds=(3,), capture_output=True, timeout=20, preexec_fn=limit)
            if result.returncode != expected or result.stderr:
                raise RuntimeError((argv[0], result.returncode, result.stdout[-500:], result.stderr[-500:]))
            return result.stdout
        try:
            fixtures = [
                ('good', window + cases, 0),
                ('rollback-mutant', window.replace(anchor, 'false') + cases, 1),
                ('count-submit', window + count_stubs + count_source + count_cases, 0),
                ('count-bypass-mutant', window + count_stubs + count_source.replace(count_guard, 'seconds') + count_cases, 1),
                ('expired-bypass-mutant', window + count_stubs + count_source.replace(
                    count_guard, '(if (> (pr-wall-ms) deadline) seconds ' + count_guard + ')') + count_cases, 1),
                ('reuse-bypass-mutant', window + count_stubs + count_source.replace('(not reuse-only)', 'true') + count_cases, 1),
                ('count-reuse', window + reuse_stubs + count_source + reuse_cases, 0),
                ('count-reuse-mutant', window + reuse_stubs + count_source.replace(reuse_decision, 'false') + reuse_cases, 1),
                ('count-wait-deadline', window + wait_stubs + count_wait + wait_cases, 0),
                ('count-wait-deadline-mutant', window + wait_stubs + count_wait.replace(wait_guard, 'false') + wait_cases, 1),
                ('resident-refresh', window + resident_stubs + dispatch + resident_cases, 0),
                ('resident-refresh-mutant', window + resident_stubs + dispatch.replace(refresh, 'again lease') + resident_cases, 1),
                ('pre-refresh-deadline-mutant', window + resident_stubs + dispatch.replace(recheck_guard, 'false') + resident_cases, 1),
            ]
            for name, part, expected in fixtures:
                text = '(ns flint-window-test (:export [main]))\n' + part
                (root / (name + '.kotoba')).write_text(text)
                run([compiler, 'compile', name + '.kotoba', '--target', 'aarch64-macos', '--output', name + '.kseed'], 0)
                output = run([compiler, 'extract-native', name + '.kseed', '--symbol', 'main', '--output', name + '.raw'], 0)
                match = re.search(rb':offset (\d+), :length (\d+), :arity 0}', output)
                if not match or int(match.group(2)) != (root / (name + '.raw')).stat().st_size:
                    raise ValueError('native extract mismatch')
                run([packager, name + '.unsigned', name + '.raw', 'fs2-cli-stdio-general', match.group(1).decode()], 0)
                image = root / (name + '.macho')
                shutil.copyfile(root / (name + '.unsigned'), image)
                image.chmod(0o700)
                subprocess.run(['/usr/bin/codesign', '--force', '--sign', '-', str(image)], check=True, capture_output=True)
                subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(image)], check=True, capture_output=True)
                shutil.rmtree(root / 'step-time', ignore_errors=True)
                shutil.rmtree(root / 'poll-seen', ignore_errors=True)
                run([image], expected)
                print(name, 'source-sha256', hashlib.sha256(text.encode()).hexdigest(), 'image-sha256', digest(image))
        finally:
            if fd != 3:
                if saved is None: os.close(3)
                else: os.dup2(saved, 3); os.close(saved)
            os.close(fd)


if __name__ == '__main__': main()
