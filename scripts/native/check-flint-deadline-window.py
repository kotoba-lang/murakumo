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
    if count_source.count(count_guard) != 1:
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
  (and count (= seconds 3)))
(defn- pr-count-wait [unit :string response :string] :i64 12)
'''
    count_cases = '''
(defn main [] :i64
 (if (and (= (pr-count [1] "/test" 10 [1] 100000 110000) 12)
          (= (pr-count [1] "/test" 10 [1] 100000 105000) -1)) 0 1))
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
                run([image], expected)
                print(name, 'source-sha256', hashlib.sha256(text.encode()).hexdigest(), 'image-sha256', digest(image))
        finally:
            if fd != 3:
                if saved is None: os.close(3)
                else: os.dup2(saved, 3); os.close(saved)
            os.close(fd)


if __name__ == '__main__': main()
