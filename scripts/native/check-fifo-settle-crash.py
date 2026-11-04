#!/usr/bin/env python3
"""Source-injected owned-process crash observations for the RGQ4 FIFO.

Requires explicit pinned diagnostic compiler and packager. This does not model
power loss, a real fsync error, process trees or a qualified governor.
"""
import argparse
import hashlib
import os
import platform
import re
import resource
import select
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
for n in ('compiler', 'packager'):
    p.add_argument('--' + n, type=Path, required=True)
    p.add_argument('--' + n + '-sha256', required=True)
a = p.parse_args()
assert (platform.system(), platform.machine()) == ('Darwin', 'arm64')
for name in ('compiler', 'packager'):
    image = getattr(a, name).resolve(strict=True)
    assert hashlib.sha256(image.read_bytes()).hexdigest() == getattr(a, name + '_sha256')
    subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(image)], check=True, capture_output=True)
    setattr(a, name, image)

def limit(): resource.setrlimit(resource.RLIMIT_CPU, (3, 3))

with tempfile.TemporaryDirectory(prefix='awai-rgq4-crash-', dir='/private/tmp') as tmp:
    root = Path(tmp); fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    if fd != 3: os.dup2(fd, 3)
    source = (ROOT / 'kotoba/resource_fifo.kotoba').read_text().replace(
        '(ns murakumo.resource-fifo (:export [resource-fifo-main]))', '(ns fifo-crash (:export [main]))', 1)
    source += '\n(defn main [] :i64 (resource-fifo-main))\n'
    helper = ('(defn- rq-test-pause [] :bool\n'
              '  (let [v (vector-alloc 262144) s (string-from-utf8 (bytes-from-vector-i64 v))\n'
              '        sent (typed-cap-call 37 :string :string s)] true))\n')
    anchors = {
        'rename': '(= (rq-fs "L" release (str root "/queue.reservation.last")) "1")',
        'receipt': '(rq-write? receipt expected-hold)',
        'consume': '(= (rq-fs "L" receipt (str root "/queue.reservation.previous")) "1")',
    }
    variants = {'good': source}
    for name, anchor in anchors.items():
        assert source.count(anchor) == 1, name
        variants[name] = (source.replace('(defn- rq-commit ', helper + '(defn- rq-commit ', 1)
                          .replace(anchor, '(and ' + anchor + ' (rq-test-pause))', 1))
    start, end = source.index('(defn- rq-history-clear? '), source.index('(defn- rq-consume-history? ')
    history = source[start:end]
    sync = '(= (rq-fs "M" receipt "") "1")'
    assert history.count(sync) == 1
    variants['history-sync-fault'] = source[:start] + history.replace(sync, 'false') + source[end:]
    def run(args, expected=0):
        r = subprocess.run([str(x) for x in args], cwd=root, env={'PATH': '/nonexistent'}, pass_fds=(3,),
                           capture_output=True, timeout=12, preexec_fn=limit)
        if r.returncode != expected or r.stderr:
            raise RuntimeError((args, r.returncode, r.stdout[-200:], r.stderr[-200:]))
        return r.stdout.decode().strip()
    images = {}
    for name, text in variants.items():
        (root / (name + '.kotoba')).write_text(text)
        run([a.compiler, 'compile', name + '.kotoba', '--target', 'aarch64-macos', '--output', name + '.kseed'])
        out = run([a.compiler, 'extract-native', name + '.kseed', '--symbol', 'main', '--output', name + '.raw'])
        m = re.search(r':offset (\d+), :length (\d+), :arity 0}', out)
        assert m and int(m.group(2)) == (root / (name + '.raw')).stat().st_size
        run([a.packager, name + '.unsigned', name + '.raw', 'fs2-cli-stdio-general', m.group(1)])
        image = root / (name + '.macho'); shutil.copyfile(root / (name + '.unsigned'), image); image.chmod(0o700)
        subprocess.run(['/usr/bin/codesign', '--force', '--sign', '-', str(image)], check=True, capture_output=True)
        images[name] = image
        print(name, 'source', hashlib.sha256(text.encode()).hexdigest(), 'image', hashlib.sha256(image.read_bytes()).hexdigest(), flush=True)
    first, second = 'a' * 64, 'b' * 64
    def op(case, action, ticket='', expected=0, want=''):
        args = [images['good'], '/' + case, action] + ([ticket] if ticket else [])
        actual = run(args, expected)
        if actual != want: raise ValueError((case, action, actual, want))
    def killed(case, phase, action, ticket):
        proc = subprocess.Popen([str(images[phase]), '/' + case, action, ticket], cwd=root, env={'PATH': '/nonexistent'},
                                pass_fds=(3,), stdout=subprocess.PIPE, stderr=subprocess.PIPE, preexec_fn=limit)
        try:
            if not select.select([proc.stdout], [], [], 8)[0] or os.read(proc.stdout.fileno(), 1) != b'\0':
                raise RuntimeError((phase, 'pause not reached'))
            if proc.poll() is not None: raise RuntimeError((phase, 'already exited'))
            proc.kill()
            if proc.wait(timeout=3) != -9: raise RuntimeError((phase, 'not killed'))
        finally:
            if proc.poll() is None: proc.kill(); proc.wait(timeout=3)
            proc.stdout.close(); proc.stderr.close()
    for phase in ('rename', 'receipt', 'consume'):
        q = root / phase; q.mkdir(); (q / 'queue.lock').write_text('')
        (q / 'queue').write_text('RGQ4\n' + first + '\n' + second + '\n')
        op(phase, 'reserve', first, want='fifo/reserved')
        op(phase, 'release', first, want='fifo/release-pending')
        op(phase, 'dequeue', first, want='fifo/dequeued')
        if phase == 'consume':
            op(phase, 'settle', first, want='fifo/settled')
            killed(phase, phase, 'reserve', second)
        else:
            killed(phase, phase, 'settle', first)
        if phase == 'receipt':
            # Visible receipt is re-read and synced by the uninstrumented host.
            fault = run([images['history-sync-fault'], '/' + phase, 'front'], 1)
            if fault != 'fifo/reservation-unknown': raise ValueError(('receipt sync fault', fault))
            op(phase, 'front', want='fifo/front ' + second)
        else:
            op(phase, 'front', expected=1, want='fifo/reservation-unknown')
            op(phase, 'settle-recover', first, want='fifo/settled')
            op(phase, 'front', want='fifo/front ' + second)
        op(phase, 'reserve', second, want='fifo/reserved')
        print('PASS', phase, 'owned process killed, expected recovery and next reservation', flush=True)
    if fd != 3: os.close(fd)
    os.close(3)
