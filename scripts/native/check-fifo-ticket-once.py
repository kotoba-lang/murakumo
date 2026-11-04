#!/usr/bin/env python3
"""Source-bound RGQ5 diagnostic; not a trusted supervisor or governor."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import resource
import select
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'kotoba/resource_fifo.kotoba'


def pinned(path, digest):
    path = path.resolve(strict=True)
    if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        raise ValueError('image hash mismatch')
    subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(path)],
                   check=True, capture_output=True, timeout=10)
    return path


def limit():
    resource.setrlimit(resource.RLIMIT_CPU, (3, 3))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler', type=Path, required=True)
    parser.add_argument('--compiler-sha256', required=True)
    parser.add_argument('--packager', type=Path, required=True)
    parser.add_argument('--packager-sha256', required=True)
    parser.add_argument('--old-fifo', type=Path, required=True)
    parser.add_argument('--old-sha256', required=True)
    args = parser.parse_args()
    compiler = pinned(args.compiler, args.compiler_sha256)
    packager = pinned(args.packager, args.packager_sha256)
    old = pinned(args.old_fifo, args.old_sha256)
    source = SOURCE.read_text()
    source = '(ns fifo-ticket-once (:export [main]))\n' + source[source.index('(defn- rq-arg'):]
    source += '\n(defn main [] :i64 (resource-fifo-main))\n'
    hook = '(= (rq-fs "M" path "") "1") (= (rq-fs "M" root "") "1")) "fifo/ticket-claimed"'
    if source.count(hook) != 1:
        raise ValueError('claim fault anchor changed')
    fault = source.replace(hook, hook.replace(')) "fifo/ticket-claimed"', ') false) "fifo/ticket-claimed"'))
    guard = '(cond (= status "1") "fifo/ticket-used"\n          (not (= status "0")) "fifo/ticket-unknown"'
    if source.count(guard) != 1:
        raise ValueError('claim guard mutation anchor changed')
    bypass = source.replace(guard, '(cond false "fifo/ticket-used"\n          false "fifo/ticket-unknown"')
    reserve_guard = ('(not (= (string-substring state 5 69) ticket)) "fifo/not-front"\n'
                     '                  (not (rq-claimed? root ticket)) "fifo/ticket-unknown"')
    if source.count(reserve_guard) != 1:
        raise ValueError('reserve marker guard mutation anchor changed')
    reserve_bypass = source.replace(reserve_guard, reserve_guard.replace('(not (rq-claimed? root ticket))', 'false'))
    pause_helper = ('(defn- rq-claim-test-pause [] :bool\n'
                    '  (let [v (vector-alloc 262144) s (string-from-utf8 (bytes-from-vector-i64 v))\n'
                    '        sent (typed-cap-call 37 :string :string s)] true))\n')
    pause_hooks = {
        'after-write': '(rq-write? path (str "RGU1\\n" ticket "\\n"))',
        'after-dir-sync': '(= (rq-fs "M" root "") "1")) "fifo/ticket-claimed"',
    }
    pause_sources = {}
    for name, anchor in pause_hooks.items():
        if source.count(anchor) != 1:
            raise ValueError('pause anchor changed: ' + name)
        replacement = ('(and ' + anchor + ' (rq-claim-test-pause))' if name == 'after-write'
                       else '(= (rq-fs "M" root "") "1") (rq-claim-test-pause)) "fifo/ticket-claimed"')
        pause_sources[name] = source.replace('(defn- rq-claim-ticket ', pause_helper + '(defn- rq-claim-ticket ', 1).replace(anchor, replacement, 1)
    with tempfile.TemporaryDirectory(prefix='awai151-ticket-once-', dir='/private/tmp') as directory:
        root = Path(directory)
        fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
        if fd != 3:
            os.dup2(fd, 3)
        def run(command, expected=0):
            result = subprocess.run([str(x) for x in command], cwd=root, pass_fds=(3,),
                                    env={'PATH': '/nonexistent'}, capture_output=True,
                                    timeout=20, preexec_fn=limit)
            if result.returncode != expected or result.stderr:
                raise RuntimeError((str(command[0]), result.returncode, result.stdout[-300:], result.stderr[-300:]))
            return result.stdout
        def build(name, text):
            (root / (name + '.kotoba')).write_text(text)
            run([compiler, 'compile', name + '.kotoba', '--target', 'aarch64-macos', '--output', name + '.kseed'])
            output = run([compiler, 'extract-native', name + '.kseed', '--symbol', 'main', '--output', name + '.raw'])
            match = re.search(rb':offset (\d+), :length (\d+), :arity 0}', output)
            if not match or int(match[2]) != (root / (name + '.raw')).stat().st_size:
                raise ValueError('bad native extraction')
            run([packager, name + '-unsigned.macho', name + '.raw', 'fs2-cli-stdio-general', match[1].decode()])
            image = root / (name + '.macho')
            shutil.copyfile(root / (name + '-unsigned.macho'), image)
            image.chmod(0o700)
            subprocess.run(['/usr/bin/codesign', '--force', '--sign', '-', str(image)], check=True, capture_output=True)
            subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(image)], check=True, capture_output=True)
            return image
        try:
            image, failed_claim = build('fifo', source), build('claim-fault', fault)
            bypass_image, reserve_mutant = build('claim-bypass', bypass), build('reserve-marker-bypass', reserve_bypass)
            pause_images = {name: build(name, text) for name, text in pause_sources.items()}
            q = root / 'q'; q.mkdir(); (q / 'queue.lock').write_text(''); (q / 'queue').write_text('RGQ5\n')
            def op(img, action, expected, ticket=None, code=0):
                command = [img, '/q', action] + ([ticket] if ticket else [])
                actual = run(command, code).decode().strip()
                if actual != expected:
                    raise ValueError((action, actual, expected))
            a, b, c = 'a' * 64, 'b' * 64, 'c' * 64
            op(old, 'front', 'fifo/state-invalid', code=1)
            for ticket in (a, b):
                op(image, 'enqueue', 'fifo/enqueued', ticket)
                if ticket == a:
                    marker = q / ('queue.used.' + ticket)
                    content = marker.read_text()
                    marker.unlink()
                    op(image, 'reserve', 'fifo/ticket-unknown', ticket, 1)
                    if (q / 'queue.reservation').exists():
                        raise ValueError('missing marker created reservation')
                    op(reserve_mutant, 'reserve', 'fifo/reserved', ticket)
                    (q / 'queue.reservation').unlink()  # isolated mutant fixture only
                    marker.write_text('malformed')
                    op(image, 'reserve', 'fifo/ticket-unknown', ticket, 1)
                    marker.write_text(content)
                op(image, 'reserve', 'fifo/reserved', ticket)
                if ticket == a:
                    marker.unlink()
                    op(image, 'release', 'fifo/ticket-unknown', ticket, 1)
                    if not (q / 'queue.reservation').exists():
                        raise ValueError('missing claim released hold')
                    marker.write_text(content)
                if ticket == b:
                    op(image, 'release', 'fifo/reservation-unknown', a, 1)
                op(image, 'release', 'fifo/release-pending', ticket)
                if ticket == a:
                    marker.write_text('bad')
                    op(image, 'dequeue', 'fifo/ticket-unknown', ticket, 1)
                    if not (q / 'queue.release.pending').exists():
                        raise ValueError('corrupt claim removed release barrier')
                    marker.write_text(content)
                op(image, 'dequeue', 'fifo/dequeued', ticket)
                if ticket == a:
                    marker.unlink()
                    op(image, 'settle', 'fifo/ticket-unknown', ticket, 1)
                    (q / 'queue.release.pending').rename(q / 'queue.reservation.last')
                    op(image, 'settle-recover', 'fifo/reservation-unknown', ticket, 1)
                    if (q / 'queue.reservation.settled').exists():
                        raise ValueError('missing claim settled recovery')
                    marker.write_text(content)
                    op(image, 'settle-recover', 'fifo/settled', ticket)
                else:
                    op(image, 'settle', 'fifo/settled', ticket)
            op(image, 'enqueue', 'fifo/ticket-used', a, 1)
            op(failed_claim, 'enqueue', 'fifo/ticket-unknown', c, 1)
            op(image, 'enqueue', 'fifo/ticket-used', c, 1)
            op(image, 'front', 'fifo/empty')
            for phase, paused_image in pause_images.items():
                case = root / phase; case.mkdir()
                (case / 'queue.lock').write_text(''); (case / 'queue').write_text('RGQ5\n')
                child = subprocess.Popen([str(paused_image), '/' + phase, 'enqueue', c], cwd=root,
                                         pass_fds=(3,), env={'PATH': '/nonexistent'}, preexec_fn=limit,
                                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                try:
                    if not select.select([child.stdout], [], [], 8)[0] or os.read(child.stdout.fileno(), 1) != b'\0':
                        raise RuntimeError('claim pause not reached: ' + phase)
                    marker = case / ('queue.used.' + c)
                    if marker.read_text() != 'RGU1\n' + c + '\n' or (case / 'queue').read_text() != 'RGQ5\n':
                        raise ValueError('unexpected pre-kill claim state: ' + phase)
                    if child.poll() is not None:
                        raise RuntimeError('claim process exited before kill: ' + phase)
                    child.kill()
                    if child.wait(timeout=3) != -9:
                        raise RuntimeError('claim process was not killed: ' + phase)
                finally:
                    if child.poll() is None:
                        child.kill(); child.wait(timeout=3)
                    child.stdout.close(); child.stderr.close()
                result = run([image, '/' + phase, 'enqueue', c], 1).decode().strip()
                if result != 'fifo/ticket-used' or (case / 'queue').read_text() != 'RGQ5\n':
                    raise ValueError(('post-kill retry', phase, result))
                print('PASS claim owner killed at', phase, '; retry refused', flush=True)
            op(bypass_image, 'enqueue', 'fifo/enqueued', a)
            print('PASS used-marker guard bypass admitted a reused ticket')
            (q / 'queue').write_text('RGQ4\n')
            op(image, 'front', 'fifo/state-invalid', code=1)
            print('PASS prior and older tickets refused; failed claim remains burned; old/new formats refuse')
            print('source_sha256', hashlib.sha256(SOURCE.read_bytes()).hexdigest())
            print('image_sha256', hashlib.sha256(image.read_bytes()).hexdigest())
        finally:
            os.close(3)
            if fd != 3:
                os.close(fd)


if __name__ == '__main__':
    main()
