#!/usr/bin/env python3
"""Run the source-bound FIFO boundary cases on an explicit diagnostic Mac runtime.

Python stages files and observes native compiler/packager/programs. This does
not qualify a provider, a crash-safe host, resource reservations or a governor.
"""
import argparse
import concurrent.futures
import fcntl
import hashlib
import json
import os
import platform
import re
import select
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPONENT = ROOT / 'kotoba/resource_fifo.kotoba'
CASES = ROOT / 'test/native/resource_fifo_bounded_cases.kotoba'
ANCHOR = '(> size maximum)'


def sha(raw): return hashlib.sha256(raw).hexdigest()


def verify(path, pin):
    if path.is_symlink() or not re.fullmatch('[0-9a-f]{64}', pin): raise ValueError('bad input')
    path = path.resolve(strict=True)
    if not path.is_file() or sha(path.read_bytes()) != pin: raise ValueError('image hash mismatch')
    subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(path)],
                   check=True, capture_output=True, timeout=30)
    return path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--compiler', required=True, type=Path); p.add_argument('--compiler-sha256', required=True)
    p.add_argument('--packager', required=True, type=Path); p.add_argument('--packager-sha256', required=True)
    p.add_argument('--artifact-dir', type=Path, help='optional new directory for the diagnostic FIFO image')
    p.add_argument('--legacy-fifo', type=Path, help='optional pinned old FIFO negative control')
    p.add_argument('--legacy-sha256', help='SHA-256 of the old FIFO')
    a = p.parse_args()
    if bool(a.legacy_fifo) != bool(a.legacy_sha256): p.error('both legacy arguments are required')
    if (platform.system(), platform.machine()) != ('Darwin', 'arm64'): raise ValueError('Mac ARM64 required')
    compiler, packager = verify(a.compiler, a.compiler_sha256), verify(a.packager, a.packager_sha256)
    legacy = verify(a.legacy_fifo, a.legacy_sha256) if a.legacy_fifo else None
    raw_component, raw_cases = COMPONENT.read_bytes(), CASES.read_bytes()
    component, cases = raw_component.decode(), raw_cases.decode()
    if component.count(ANCHOR) != 1: raise ValueError('negative control anchor changed')
    component = component[component.index('(defn- rq-arg'):]
    cases = cases[cases.index('(defn test-fifo-meta-size'):]
    unity = ('(ns fifo-boundary-fixture (:export [main]))\n' + component + '\n' + cases +
             '\n(defn main [] :i64 (if (and (test-fifo-meta-size) (test-fifo-bounded-reads "/cases")) 0 1))\n')
    with tempfile.TemporaryDirectory(prefix='awai151-fifo-boundary-', dir='/private/tmp') as tmp:
        root = Path(tmp); scope = root / 'cases'; scope.mkdir(mode=0o700)
        for name, data in [('small', b'RGQ4\n'), ('queue-limit', b'x'*4165), ('queue-over', b'x'*4166),
                           ('journal-limit', b'x'*8340), ('journal-over', b'x'*8341), ('empty', b'')]:
            (scope / name).write_bytes(data)
        (root / 'fixture.kotoba').write_text(unity)
        (root / 'mutant.kotoba').write_text(unity.replace(ANCHOR, 'false'))
        (root / 'fifo.kotoba').write_text('(ns fifo-contract-fixture (:export [main]))\n' + component +
                                         '\n(defn main [] :i64 (resource-fifo-main))\n')
        read_fixture = ('(ns fifo-read-fault-fixture (:export [main]))\n' + component +
                        '\n(defn main [] :i64 (if (= (rq-read-bounded "/cases/small" 4165) "") 0 1))\n')
        read_anchor = ('r (typed-cap-call 35 :string :bytes (str "\\u0000FS2\\u0000E" path '
                       '"\\u00000:" (string-from-i64 size)))')
        size_anchor = '(= (rq-size path) size)'
        if read_fixture.count(read_anchor) != 1 or read_fixture.count(size_anchor) != 1:
            raise ValueError('bounded read fault anchor changed')
        (root / 'read-control.kotoba').write_text(read_fixture)
        (root / 'read-short.kotoba').write_text(read_fixture.replace(
            read_anchor, read_anchor.replace('r (typed-cap-call', 'r0 (typed-cap-call') +
            '\n            r (bytes-slice r0 0 (- (bytes-count r0) 1))'))
        (root / 'read-size-change.kotoba').write_text(read_fixture.replace(
            size_anchor, '(= (+ (rq-size path) 1) size)'))
        read_pause_helper = ('(defn- rq-read-test-pause [] :bool\n'
                             '  (let [v (vector-alloc 262144) s (string-from-utf8 (bytes-from-vector-i64 v))\n'
                             '        sent (typed-cap-call 37 :string :string s)] true))\n')
        read_race_source = read_fixture.replace(
            '(defn- rq-read-bounded ', read_pause_helper + '(defn- rq-read-bounded ', 1).replace(
            size_anchor, '(and (rq-read-test-pause) ' + size_anchor + ')', 1)
        (root / 'read-race.kotoba').write_text(read_race_source)
        read_expected = '(if (= (rq-read-bounded "/cases/small" 4165) "") 0 1)'
        if read_race_source.count(read_expected) != 1: raise ValueError('read race result anchor changed')
        (root / 'read-same-size.kotoba').write_text(read_race_source.replace(
            read_expected, '(if (= (rq-read-bounded "/cases/small" 4165) "RGQ4\\n") 0 1)'))
        plain = (root / 'fifo.kotoba').read_text()
        hold_guard = '(= hold-status "1") "fifo/reservation-held"'
        if plain.count(hold_guard) != 1:
            raise ValueError('reservation guard mutation anchor changed')
        (root / 'reserve-bypass.kotoba').write_text(plain.replace(
            hold_guard, '(= hold-status "never") "fifo/reservation-held"'))
        history_guard = '(not (rq-history-clear? root)) "fifo/reservation-unknown"'
        if plain.count(history_guard) != 1:
            raise ValueError('history guard mutation anchor changed')
        (root / 'history-bypass.kotoba').write_text(plain.replace(
            history_guard, 'false "fifo/reservation-unknown"'))
        pause = '(rq-test-pause)'
        helper = ('(defn- rq-test-pause [] :bool\n'
                  '  (let [v (vector-alloc 262144) s (string-from-utf8 (bytes-from-vector-i64 v))\n'
                  '        sent (typed-cap-call 37 :string :string s)] true))\n')
        anchors = {
            'after-intent': '(= (rq-fs "M" root "") "1") (rq-write? tmp next)',
            'after-pending': '(= (rq-fs "M" tmp "") "1") (= (rq-read-bounded tmp 4165) next)',
            'after-rename': '(= (rq-fs "L" tmp state) "1")\n         (= (rq-fs "M" root "") "1")',
            'after-sync': '(= (rq-fs "M" root "") "1") (= (rq-fs "L" intent (str root "/queue.last")) "1")',
            'after-archive': '(= (rq-fs "L" intent (str root "/queue.last")) "1")\n         (= (rq-fs "M" root "") "1")',
        }
        for phase, anchor in anchors.items():
            if plain.count(anchor) != 1 or plain.count('(defn- rq-commit ') != 1:
                raise ValueError('pause anchor changed: ' + phase)
            cut = anchor.index('"1")') + len('"1")')
            instrumented = anchor[:cut] + ' ' + pause + anchor[cut:]
            (root / (phase + '.kotoba')).write_text(plain.replace('(defn- rq-commit ', helper + '(defn- rq-commit ')
                                                    .replace(anchor, instrumented))
        reserve_anchor = '(= (rq-fs "M" hold "") "1") (= (rq-fs "M" root "") "1")) "fifo/reserved"'
        if plain.count(reserve_anchor) != 1:
            raise ValueError('reservation pause anchor changed')
        (root / 'reserve-pause.kotoba').write_text(plain.replace('(defn- rq-commit ', helper + '(defn- rq-commit ')
            .replace(reserve_anchor, reserve_anchor.replace(')) "fifo/reserved"', ') ' + pause + ') "fifo/reserved"')))
        release_anchor = '(= (rq-fs "L" hold release) "1")\n                       (= (rq-fs "M" root "") "1")) "fifo/release-pending"'
        if plain.count(release_anchor) != 1:
            raise ValueError('release fault anchor changed')
        (root / 'release-sync-fault.kotoba').write_text(plain.replace(
            release_anchor, release_anchor.replace('(= (rq-fs "M" root "") "1")', 'false')))
        (root / 'release-pause.kotoba').write_text(plain.replace('(defn- rq-commit ', helper + '(defn- rq-commit ')
            .replace(release_anchor, release_anchor.replace('"1")\n', '"1") ' + pause + '\n', 1)))
        settle_anchor = ('(= (rq-fs "L" release (str root "/queue.reservation.last")) "1")\n'
                         '                               (= (rq-fs "M" root "") "1")')
        if plain.count(settle_anchor) != 1:
            raise ValueError('settle fault anchor changed')
        (root / 'settle-sync-fault.kotoba').write_text(plain.replace(
            settle_anchor, settle_anchor.replace('(= (rq-fs "M" root "") "1")', 'false')))
        recovery_start = plain.index('(defn- rq-recover ')
        recovery_end = plain.index('(defn- rq-commit ', recovery_start)
        recovery_source = plain[recovery_start:recovery_end]
        faults = {
            'queue-sync': '(= (rq-fs "M" (str root "/queue") "") "1")',
            'intent-sync': '(= (rq-fs "M" intent "") "1")',
            'directory-sync': '(= (rq-fs "M" root "") "1")',
            'post-archive-sync': '(= (rq-fs "M" root "") "1")',
        }
        for fault, anchor in faults.items():
            count = recovery_source.count(anchor)
            if count != (2 if fault in ('directory-sync', 'post-archive-sync') else 1):
                raise ValueError('recovery fault anchor changed: ' + fault)
            if fault == 'post-archive-sync':
                at = recovery_source.rindex(anchor)
                mutated = recovery_source[:at] + 'false' + recovery_source[at + len(anchor):]
            else:
                mutated = recovery_source.replace(anchor, 'false', 1)
            (root / (fault + '.kotoba')).write_text(plain[:recovery_start] + mutated + plain[recovery_end:])
        fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY); saved = None
        if fd != 3:
            try: saved = os.dup(3)
            except OSError: pass
            os.dup2(fd, 3)
        try:
            def run(argv, expected):
                r = subprocess.run([str(x) for x in argv], cwd=root, pass_fds=(3,),
                                   capture_output=True, timeout=180, env={'PATH':'/nonexistent'})
                if r.returncode != expected or r.stderr:
                    raise RuntimeError((str(argv[0]), r.returncode, r.stdout[-300:], r.stderr[-300:]))
                return r.stdout
            def build(name):
                run([compiler, 'compile', name+'.kotoba', '--target', 'aarch64-macos', '--output', name+'.kseed'], 0)
                output = run([compiler, 'extract-native', name+'.kseed', '--symbol', 'main', '--output', name+'.raw'], 0)
                m = re.search(rb':offset (\d+), :length (\d+), :arity 0}', output)
                if not m or int(m.group(2)) != (root/(name+'.raw')).stat().st_size: raise ValueError('extract mismatch')
                run([packager, name+'-unsigned.macho', name+'.raw', 'fs2-cli-stdio-general', m.group(1).decode()], 0)
                image = root/(name+'.macho'); shutil.copyfile(root/(name+'-unsigned.macho'), image); image.chmod(0o700)
                subprocess.run(['/usr/bin/codesign', '--force', '--sign', '-', str(image)], check=True, capture_output=True, timeout=30)
                subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(image)], check=True, capture_output=True, timeout=30)
                return image
            image, mutant = build('fixture'), build('mutant')
            run([image], 0); run([mutant], 1)
            run([build('read-control')], 1)
            run([build('read-short')], 0)
            run([build('read-size-change')], 0)
            read_race = build('read-race')
            for changed, expected_exit in [(False, 1), (True, 0)]:
                small = scope / 'small'
                small.write_bytes(b'RGQ4\n')
                child = subprocess.Popen([str(read_race)], cwd=root, pass_fds=(3,),
                                         stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                         env={'PATH':'/nonexistent'})
                try:
                    if not select.select([child.stdout], [], [], 10)[0] or os.read(child.stdout.fileno(), 1) != b'\0':
                        raise RuntimeError('read race pause not reached')
                    if child.poll() is not None: raise RuntimeError('read race process exited early')
                    if changed: small.write_bytes(b'X')
                    stdout, stderr = child.communicate(timeout=10)
                    if child.returncode != expected_exit or stderr or len(stdout) != 262143 or any(stdout):
                        raise ValueError(('read race result', changed, child.returncode, len(stdout), stderr))
                finally:
                    if child.poll() is None: child.kill(); child.wait(timeout=3)
            small.write_bytes(b'RGQ4\n')
            same_size = build('read-same-size')
            child = subprocess.Popen([str(same_size)], cwd=root, pass_fds=(3,),
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     env={'PATH':'/nonexistent'})
            try:
                if not select.select([child.stdout], [], [], 10)[0] or os.read(child.stdout.fileno(), 1) != b'\0':
                    raise RuntimeError('same-size race pause not reached')
                if child.poll() is not None: raise RuntimeError('same-size race process exited early')
                small.write_bytes(b'XXXX\n')
                stdout, stderr = child.communicate(timeout=10)
                if child.returncode != 0 or stderr or len(stdout) != 262143 or any(stdout):
                    raise ValueError(('same-size race result', child.returncode, len(stdout), stderr))
                if small.read_bytes() != b'XXXX\n': raise ValueError('same-size replacement not visible')
            finally:
                if child.poll() is None: child.kill(); child.wait(timeout=3)
            small.write_bytes(b'RGQ4\n')
            fifo = build('fifo')
            reserve_bypass = build('reserve-bypass')
            queue = root / 'queuecase'; queue.mkdir(mode=0o700)
            (queue / 'queue.lock').write_text(''); (queue / 'queue').write_text('RGQ4\n')
            if legacy:
                old = subprocess.run([str(legacy), '/queuecase', 'front'], cwd=root, pass_fds=(3,),
                                     capture_output=True, timeout=15, env={'PATH':'/nonexistent'})
                if old.returncode != 1 or old.stdout != b'fifo/state-invalid\n' or old.stderr:
                    raise ValueError(('old FIFO did not refuse RGQ4', old.returncode, old.stdout, old.stderr))
            first, second, third = 'a'*64, 'b'*64, 'c'*64
            # A lost settle sync acknowledgement must retain a barrier until
            # the exact ticket and journal are explicitly reconciled.
            lost_queue = root / 'settle-unknown'; lost_queue.mkdir(mode=0o700)
            (lost_queue / 'queue.lock').write_text('')
            (lost_queue / 'queue').write_text('RGQ4\n' + first + '\n' + second + '\n')
            lost_image = build('settle-sync-fault')
            history_bypass = build('history-bypass')
            def lost_op(action, ticket=None, image=fifo):
                args = [str(image), '/settle-unknown', action] + ([ticket] if ticket else [])
                r = subprocess.run(args, cwd=root, pass_fds=(3,), capture_output=True,
                                   timeout=15, env={'PATH':'/nonexistent'})
                if r.stderr: raise RuntimeError(('unexpected settle test stderr', r.stderr))
                return r.returncode, r.stdout.decode().strip()
            for action, ticket, expected in [('reserve', first, (0, 'fifo/reserved')),
                                              ('release', first, (0, 'fifo/release-pending')),
                                              ('dequeue', first, (0, 'fifo/dequeued'))]:
                if lost_op(action, ticket) != expected: raise ValueError(('settle setup failed', action))
            if lost_op('settle', first, lost_image) != (1, 'fifo/reservation-unknown'):
                raise ValueError('settle sync failure was not unknown')
            if lost_op('front') != (1, 'fifo/reservation-unknown'):
                raise ValueError('unknown settle exposed the next ticket')
            if lost_op('front', image=history_bypass) != (0, 'fifo/front ' + second):
                raise ValueError('history bypass did not expose the counterexample')
            if lost_op('settle-recover', second) != (1, 'fifo/reservation-unknown'):
                raise ValueError('wrong ticket reconciled settle')
            if lost_op('settle-recover', first) != (0, 'fifo/settled'):
                raise ValueError('matching settle could not be reconciled')
            if lost_op('front') != (0, 'fifo/front ' + second):
                raise ValueError('reconciled ticket was not exposed')
            def op(action, ticket=None):
                args = [str(fifo), '/queuecase', action] + ([ticket] if ticket else [])
                r = subprocess.run(args, cwd=root, pass_fds=(3,), capture_output=True,
                                   timeout=15, env={'PATH':'/nonexistent'})
                if r.stderr: raise RuntimeError(('unexpected fifo stderr', r.stderr))
                return r.returncode, r.stdout.decode().strip()
            def expect(action, result, ticket=None):
                actual = op(action, ticket)
                if actual != result: raise RuntimeError((action, result, actual))
            (queue / 'queue').write_text('RGQ1\n')
            expect('front', (1, 'fifo/state-invalid'))
            (queue / 'queue').write_text('RGQ2\n')
            expect('front', (1, 'fifo/state-invalid'))
            (queue / 'queue').write_text('RGQ3\n')
            expect('front', (1, 'fifo/state-invalid'))
            (queue / 'queue').write_text('RGQ4\n')
            expect('front', (0, 'fifo/empty'))
            expect('enqueue', (0, 'fifo/enqueued'), first)
            expect('enqueue', (0, 'fifo/enqueued'), second)
            expect('enqueue', (1, 'fifo/already-queued'), first)
            expect('dequeue', (1, 'fifo/release-required'), first)
            expect('cancel', (1, 'fifo/cancel-front-refused'), first)
            expect('enqueue', (0, 'fifo/enqueued'), third)
            expect('cancel', (0, 'fifo/cancelled'), third)
            expect('reserve', (1, 'fifo/not-front'), second)
            expect('reserve', (0, 'fifo/reserved'), first)
            if (queue / 'queue.reservation').read_text() != 'RGH1\n' + first + '\n':
                raise ValueError('reservation bytes mismatch')
            expect('front', (1, 'fifo/reservation-held'))
            bypass = subprocess.run([str(reserve_bypass), '/queuecase', 'front'], cwd=root, pass_fds=(3,),
                                    capture_output=True, timeout=15, env={'PATH':'/nonexistent'})
            if bypass.returncode != 0 or bypass.stdout.decode().strip() != 'fifo/front ' + first or bypass.stderr:
                raise ValueError('reservation bypass negative control did not reach the held front')
            expect('dequeue', (1, 'fifo/reservation-held'), first)
            expect('enqueue', (1, 'fifo/reservation-held'), third)
            expect('reserve', (1, 'fifo/reservation-held'), first)
            expect('release', (1, 'fifo/reservation-unknown'), second)
            expect('release', (0, 'fifo/release-pending'), first)
            expect('front', (1, 'fifo/release-pending'))
            expect('release', (1, 'fifo/reservation-unknown'), first)
            expect('settle', (1, 'fifo/reservation-unknown'), first)
            expect('dequeue', (0, 'fifo/dequeued'), first)
            expect('front', (1, 'fifo/release-pending'))
            expect('settle', (0, 'fifo/settled'), first)
            # Reset this private synthetic queue for the killed-owner case.
            (queue / 'queue').write_text('RGQ4\n' + first + '\n' + second + '\n')
            for variant_name in ('release-sync-fault', 'release-pause'):
                release_image = build(variant_name)
                expect('reserve', (0, 'fifo/reserved'), first)
                args = [str(release_image), '/queuecase', 'release', first]
                if variant_name == 'release-sync-fault':
                    result = subprocess.run(args, cwd=root, pass_fds=(3,), capture_output=True,
                                            timeout=15, env={'PATH':'/nonexistent'})
                    if result.returncode != 1 or result.stdout != b'fifo/reservation-unknown\n' or result.stderr:
                        raise ValueError('release sync fault did not remain unknown')
                else:
                    stopped = subprocess.Popen(args, cwd=root, pass_fds=(3,), stdout=subprocess.PIPE,
                                               stderr=subprocess.PIPE, env={'PATH':'/nonexistent'})
                    try:
                        if not select.select([stopped.stdout], [], [], 10)[0] or os.read(stopped.stdout.fileno(), 1) != b'\0':
                            raise RuntimeError('release pause not reached')
                        if stopped.poll() is not None: raise RuntimeError('release owner already exited')
                        stopped.kill()
                        if stopped.wait(timeout=3) != -9: raise RuntimeError('release owner not killed')
                    finally:
                        if stopped.poll() is None: stopped.kill(); stopped.wait(timeout=3)
                        stopped.stdout.close(); stopped.stderr.close()
                expect('front', (1, 'fifo/release-pending'))
                expect('enqueue', (1, 'fifo/release-pending'), third)
                expect('dequeue', (0, 'fifo/dequeued'), first)
                expect('front', (1, 'fifo/release-pending'))
                expect('settle', (0, 'fifo/settled'), first)
                (queue / 'queue').write_text('RGQ4\n' + first + '\n' + second + '\n')
            (queue / 'queue.reservation').write_text('partial')
            expect('front', (1, 'fifo/reservation-held'))
            expect('release', (1, 'fifo/reservation-unknown'), first)
            (queue / 'queue.reservation').unlink()
            expect('front', (0, 'fifo/front '+first))
            reserved_variant = build('reserve-pause')
            killed = subprocess.Popen([str(reserved_variant), '/queuecase', 'reserve', first], cwd=root,
                                      pass_fds=(3,), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                      env={'PATH':'/nonexistent'})
            try:
                if not select.select([killed.stdout], [], [], 10)[0] or os.read(killed.stdout.fileno(), 1) != b'\0':
                    raise RuntimeError('reservation pause not reached')
                if killed.poll() is not None:
                    raise RuntimeError('reservation owner exited before kill')
                killed.kill()
                if killed.wait(timeout=3) != -9:
                    raise RuntimeError('reservation owner was not killed')
            finally:
                if killed.poll() is None:
                    killed.kill(); killed.wait(timeout=3)
                killed.stdout.close(); killed.stderr.close()
            expect('front', (1, 'fifo/reservation-held'))
            expect('dequeue', (1, 'fifo/reservation-held'), first)
            expect('release', (0, 'fifo/release-pending'), first)
            expect('dequeue', (1, 'fifo/reservation-unknown'), second)
            expect('cancel', (1, 'fifo/release-pending'), first)
            expect('dequeue', (0, 'fifo/dequeued'), first)
            expect('front', (1, 'fifo/release-pending'))
            expect('settle', (0, 'fifo/settled'), first)
            expect('front', (0, 'fifo/front '+second))
            # Clear the private fixture only after the settled test.
            (queue / 'queue').write_text('RGQ4\n')
            (queue / 'queue.pending').write_text('partial')
            expect('enqueue', (1, 'fifo/pending-unknown'), third)
            (queue / 'queue.pending').unlink()
            old = 'RGQ4\n'; new = old + first + '\n'
            (queue / 'queue.intent').write_text('RGI1\n' + old + 'NEXT\n' + new)
            expect('front', (1, 'fifo/pending-unknown'))
            expect('recover', (0, 'fifo/aborted'))
            if (queue / 'queue').read_text() != old: raise ValueError('aborted state changed')
            (queue / 'queue').write_text(new)
            (queue / 'queue.intent').write_text('RGI1\n' + old + 'NEXT\n' + new)
            expect('recover', (0, 'fifo/recovered'))
            expect('front', (0, 'fifo/front '+first))
            (queue / 'queue').write_text(old)
            with (queue / 'queue.lock').open('r+') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                expect('front', (1, 'fifo/busy'))
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                tickets = [format(i, '064x') for i in range(8)]
                outcomes = list(pool.map(lambda t: op('enqueue', t), tickets))
            if any(x not in ((0, 'fifo/enqueued'), (1, 'fifo/busy')) for x in outcomes):
                raise ValueError(('unexpected concurrent outcome', outcomes))
            accepted = [ticket for ticket, outcome in zip(tickets, outcomes) if outcome == (0, 'fifo/enqueued')]
            lines = (queue / 'queue').read_text().splitlines()
            if lines[0] != 'RGQ4' or len(lines[1:]) != len(accepted) or set(lines[1:]) != set(accepted):
                raise ValueError('concurrent state mismatch')
            killed_phases = []; variants = {}
            for phase in anchors:
                variant = build(phase)
                variants[phase] = variant
                phase_root = root / ('kill-' + phase); phase_root.mkdir(mode=0o700)
                (phase_root / 'queue.lock').write_text(''); (phase_root / 'queue').write_text(old)
                child = subprocess.Popen([str(variant), '/kill-' + phase, 'enqueue', first], cwd=root,
                                         pass_fds=(3,), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                         env={'PATH':'/nonexistent'})
                try:
                    if not select.select([child.stdout], [], [], 10)[0] or os.read(child.stdout.fileno(), 1) != b'\0':
                        raise RuntimeError('pause not reached: ' + phase)
                    if child.poll() is not None: raise RuntimeError('paused process exited: ' + phase)
                    intent = (phase_root / 'queue.intent').exists()
                    pending = (phase_root / 'queue.pending').exists()
                    state = (phase_root / 'queue').read_text()
                    expected_new = phase in ('after-rename', 'after-sync', 'after-archive')
                    if intent != (phase != 'after-archive') or state != (new if expected_new else old):
                        raise ValueError(('unexpected stopped state', phase, intent, pending, state))
                    child.kill()
                    if child.wait(timeout=3) != -9: raise ValueError('owned child was not killed')
                    check = subprocess.run([str(fifo), '/kill-' + phase, 'front'], cwd=root, pass_fds=(3,),
                                           capture_output=True, timeout=15, env={'PATH':'/nonexistent'})
                    expected_front = (0, 'fifo/front '+first) if phase == 'after-archive' else (1, 'fifo/pending-unknown')
                    if check.stderr or (check.returncode, check.stdout.decode().strip()) != expected_front:
                        raise ValueError(('unexpected post-kill result', phase, check.returncode, check.stdout, check.stderr))
                    recovery = subprocess.run([str(fifo), '/kill-' + phase, 'recover'], cwd=root, pass_fds=(3,),
                                              capture_output=True, timeout=15, env={'PATH':'/nonexistent'})
                    expected_recovery = {
                        'after-intent': (0, 'fifo/aborted'),
                        'after-pending': (1, 'fifo/recovery-unresolved'),
                        'after-rename': (0, 'fifo/recovered'),
                        'after-sync': (0, 'fifo/recovered'),
                        'after-archive': (1, 'fifo/no-intent'),
                    }[phase]
                    if recovery.stderr or (recovery.returncode, recovery.stdout.decode().strip()) != expected_recovery:
                        raise ValueError(('unexpected reconciliation', phase, recovery.returncode,
                                          recovery.stdout, recovery.stderr))
                    if (phase_root / 'queue').read_text() != (new if expected_new else old):
                        raise ValueError(('reconciliation changed queue', phase))
                    killed_phases.append(phase)
                finally:
                    if child.poll() is None: child.kill(); child.wait(timeout=3)
            for phase, variant in variants.items():
                case = root / ('release-kill-' + phase); case.mkdir(mode=0o700)
                (case / 'queue.lock').write_text('')
                (case / 'queue').write_text('RGQ4\n' + first + '\n')
                (case / 'queue.release.pending').write_text('RGH1\n' + first + '\n')
                path = '/release-kill-' + phase
                child = subprocess.Popen([str(variant), path, 'dequeue', first], cwd=root, pass_fds=(3,),
                                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, env={'PATH':'/nonexistent'})
                try:
                    if not select.select([child.stdout], [], [], 10)[0] or os.read(child.stdout.fileno(), 1) != b'\0':
                        raise RuntimeError('release dequeue pause not reached: ' + phase)
                    if child.poll() is not None: raise RuntimeError('release dequeue already exited')
                    child.kill()
                    if child.wait(timeout=3) != -9: raise ValueError('release dequeue owner not killed')
                finally:
                    if child.poll() is None: child.kill(); child.wait(timeout=3)
                    child.stdout.close(); child.stderr.close()
                def release_op(action, ticket=None):
                    argv = [str(fifo), path, action] + ([ticket] if ticket else [])
                    r = subprocess.run(argv, cwd=root, pass_fds=(3,), capture_output=True,
                                       timeout=15, env={'PATH':'/nonexistent'})
                    if r.stderr: raise ValueError('release recovery stderr')
                    return r.returncode, r.stdout.decode().strip()
                if release_op('front') != (1, 'fifo/release-pending'):
                    raise ValueError('release barrier lost after dequeue kill: ' + phase)
                recovered = release_op('recover')
                expected = {'after-intent': (0, 'fifo/aborted'), 'after-pending': (1, 'fifo/recovery-unresolved'),
                            'after-rename': (0, 'fifo/recovered'), 'after-sync': (0, 'fifo/recovered'),
                            'after-archive': (1, 'fifo/no-intent')}[phase]
                if recovered != expected: raise ValueError(('release recovery mismatch', phase, recovered))
                if phase == 'after-pending':
                    if release_op('settle', first) != (1, 'fifo/pending-unknown'):
                        raise ValueError('unresolved release permitted settle')
                    continue
                if phase == 'after-intent' and release_op('dequeue', first) != (0, 'fifo/dequeued'):
                    raise ValueError('aborted release dequeue did not resume')
                if release_op('settle', first) != (0, 'fifo/settled'):
                    raise ValueError('reconciled release did not settle: ' + phase)
                if release_op('front') != (0, 'fifo/empty'):
                    raise ValueError('settled release did not expose queue: ' + phase)
            injected_faults = []
            for fault in faults:
                variant = build(fault)
                for label, prior in [('old', old), ('new', new)]:
                    fault_root = root / ('fault-' + fault + '-' + label); fault_root.mkdir(mode=0o700)
                    (fault_root / 'queue.lock').write_text(''); (fault_root / 'queue').write_text(prior)
                    journal = 'RGI1\n' + old + 'NEXT\n' + new
                    (fault_root / 'queue.intent').write_text(journal)
                    path = '/fault-' + fault + '-' + label
                    failed = subprocess.run([str(variant), path, 'recover'], cwd=root, pass_fds=(3,),
                                            capture_output=True, timeout=15, env={'PATH':'/nonexistent'})
                    if failed.stderr or (failed.returncode, failed.stdout.decode().strip()) != (1, 'fifo/recovery-unknown'):
                        raise ValueError(('injected fault not unknown', fault, label, failed.returncode, failed.stdout))
                    post_archive = fault == 'post-archive-sync'
                    journal_path = fault_root / ('queue.last' if post_archive else 'queue.intent')
                    other_path = fault_root / ('queue.intent' if post_archive else 'queue.last')
                    if ((fault_root / 'queue').read_text() != prior or
                            journal_path.read_text() != journal or other_path.exists()):
                        raise ValueError(('fault altered recovery state', fault, label))
                    front = subprocess.run([str(fifo), path, 'front'], cwd=root, pass_fds=(3,),
                                           capture_output=True, timeout=15, env={'PATH':'/nonexistent'})
                    expected_front = ((0, 'fifo/empty' if label == 'old' else 'fifo/front '+first)
                                      if post_archive else (1, 'fifo/pending-unknown'))
                    if front.stderr or (front.returncode, front.stdout.decode().strip()) != expected_front:
                        raise ValueError(('unexpected front', fault, label))
                    recovered = subprocess.run([str(fifo), path, 'recover'], cwd=root, pass_fds=(3,),
                                               capture_output=True, timeout=15, env={'PATH':'/nonexistent'})
                    expected = ((1, 'fifo/no-intent') if post_archive else
                                (0, 'fifo/aborted' if label == 'old' else 'fifo/recovered'))
                    if recovered.stderr or (recovered.returncode, recovered.stdout.decode().strip()) != expected:
                        raise ValueError(('recovery failed', fault, label, recovered.returncode, recovered.stdout))
                    injected_faults.append(fault + '-' + label)
            if COMPONENT.read_bytes() != raw_component or CASES.read_bytes() != raw_cases:
                raise ValueError('source changed during run')
            if sha(compiler.read_bytes()) != a.compiler_sha256 or sha(packager.read_bytes()) != a.packager_sha256:
                raise ValueError('tool changed during run')
            if a.artifact_dir:
                a.artifact_dir.mkdir(mode=0o700, parents=False, exist_ok=False)
                artifact = a.artifact_dir / 'fifo.macho'
                shutil.copyfile(fifo, artifact); artifact.chmod(0o700)
                if sha(artifact.read_bytes()) != sha(fifo.read_bytes()):
                    raise ValueError('artifact copy mismatch')
                pins = {}
                for phase, variant in variants.items():
                    target = a.artifact_dir / (phase + '.macho')
                    shutil.copyfile(variant, target); target.chmod(0o700)
                    pins[phase] = sha(target.read_bytes())
                    if pins[phase] != sha(variant.read_bytes()): raise ValueError('variant copy mismatch')
                manifest = {'fifoSha256': sha(fifo.read_bytes()), 'componentSha256': sha(raw_component),
                            'instrumented': pins}
                (a.artifact_dir / 'manifest.json').write_text(json.dumps(manifest, sort_keys=True) + '\n')
                print('artifactManifestSha256', sha((a.artifact_dir / 'manifest.json').read_bytes()), flush=True)
            print(json.dumps({'componentSha256':sha(raw_component), 'casesSha256':sha(raw_cases),
                              'fixtureSha256':sha(unity.encode()), 'compilerSha256':a.compiler_sha256,
                              'packagerSha256':a.packager_sha256, 'fifoSha256':sha(fifo.read_bytes()),
                              'positiveExit':0, 'boundRemovalMutantExit':1,
                              'sourceInjectedReadFaults':['short-response', 'changed-post-read-size'],
                              'realFileReadRace':'changed-size-refused; unchanged-control-returned-data',
                              'sameSizeRace':'old-data-accepted-after-file-change',
                              'fifoContractPassed':True, 'oldAndNewJournalRecoveryPassed':True,
                              'concurrentAccepted':len(accepted), 'concurrentBusy':8-len(accepted),
                              'ownedChildKillPhases':killed_phases,
                              'sourceInjectedSyncFaults':injected_faults,
                              'scope':'local synthetic FIFO diagnostic; not provider or governor qualification'}))
        finally:
            if fd != 3:
                if saved is None: os.close(3)
                else: os.dup2(saved, 3); os.close(saved)
            os.close(fd)


if __name__ == '__main__': main()
