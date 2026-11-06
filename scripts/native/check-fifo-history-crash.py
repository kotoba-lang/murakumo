#!/usr/bin/env python3
"""Diagnostic: kill FIFO reserve after history rename, then test recovery.

Uses a pinned native compiler and experimental FS2 packager on a private test
queue. The injected stdout pause changes the image; this is not a governor.
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
p = argparse.ArgumentParser(description=__doc__)
for name in ('compiler', 'packager'):
    p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--' + name + '-sha256', required=True)
a = p.parse_args()
if (platform.system(), platform.machine()) != ('Darwin', 'arm64'):
    raise RuntimeError('Darwin ARM64 required')
for name in ('compiler', 'packager'):
    path = getattr(a, name).resolve(strict=True)
    if hashlib.sha256(path.read_bytes()).hexdigest() != getattr(a, name + '_sha256'):
        raise RuntimeError(name + ' digest mismatch')
    subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(path)],
                   capture_output=True, check=True, timeout=10)
    setattr(a, name, path)

source = (ROOT / 'kotoba/resource_fifo.kotoba').read_text()
source = '(ns fifo-history-crash (:export [main]))\n' + source[source.index('(defn- rq-arg'):]
source += '\n(defn main [] :i64 (resource-fifo-main))\n'
anchor = '(= (rq-fs "L" receipt (str root "/queue.reservation.previous")) "1")'
if source.count(anchor) != 1:
    raise RuntimeError('history rename anchor changed')
helper = ('(defn- rq-test-pause [] :bool\n'
          '  (let [v (vector-alloc 262144) s (string-from-utf8 (bytes-from-vector-i64 v))\n'
          '        sent (typed-cap-call 37 :string :string s)] true))\n')
paused = source.replace('(defn- rq-consume-history? ', helper + '(defn- rq-consume-history? ', 1)
paused = paused.replace(anchor, anchor + ' (rq-test-pause)', 1)
def limit():
    resource.setrlimit(resource.RLIMIT_CPU, (3, 3))

with tempfile.TemporaryDirectory(prefix='awai151-history-crash-', dir='/private/tmp') as tmp:
    root = Path(tmp); q = root / 'q'; q.mkdir(mode=0o700)
    (q / 'queue.lock').write_text(''); one, two = 'a' * 64, 'b' * 64
    (q / 'queue').write_text('RGQ4\n' + one + '\n' + two + '\n')
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    if fd != 3: os.dup2(fd, 3)
    def run(args):
        r = subprocess.run([str(x) for x in args], cwd=root, pass_fds=(3,),
                           capture_output=True, env={'PATH': '/nonexistent'},
                           timeout=20, preexec_fn=limit)
        if r.returncode or r.stderr:
            raise RuntimeError((str(args[0]), r.returncode, r.stdout[-200:], r.stderr[-200:]))
        return r.stdout
    def build(name, text):
        (root / (name + '.kotoba')).write_text(text)
        run([a.compiler, 'compile', name+'.kotoba', '--target', 'aarch64-macos', '--output', name+'.kseed'])
        out = run([a.compiler, 'extract-native', name+'.kseed', '--symbol', 'main', '--output', name+'.raw'])
        m = re.search(rb':offset (\d+), :length (\d+), :arity 0}', out)
        if not m or int(m[2]) != (root / (name+'.raw')).stat().st_size:
            raise RuntimeError('native extract mismatch')
        run([a.packager, name+'-unsigned.macho', name+'.raw', 'fs2-cli-stdio-general', m[1].decode()])
        image = root / (name+'.macho'); shutil.copyfile(root / (name+'-unsigned.macho'), image); image.chmod(0o700)
        subprocess.run(['/usr/bin/codesign', '--force', '--sign', '-', str(image)], check=True, capture_output=True)
        return image
    good, fault = build('good', source), build('pause', paused)
    def op(action, ticket='', expected=0, text=''):
        r = subprocess.run([str(good), '/q', action] + ([ticket] if ticket else []), cwd=root,
                           pass_fds=(3,), capture_output=True, env={'PATH':'/nonexistent'}, timeout=10, preexec_fn=limit)
        if (r.returncode, r.stdout.decode().strip(), r.stderr) != (expected, text, b''):
            raise RuntimeError((action, r.returncode, r.stdout, r.stderr))
    op('reserve', one, text='fifo/reserved')
    op('release', one, text='fifo/release-pending')
    op('dequeue', one, text='fifo/dequeued')
    op('settle', one, text='fifo/settled')
    child = subprocess.Popen([str(fault), '/q', 'reserve', two], cwd=root, pass_fds=(3,),
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             env={'PATH':'/nonexistent'}, preexec_fn=limit)
    try:
        if not select.select([child.stdout], [], [], 10)[0] or os.read(child.stdout.fileno(), 1) != b'\0':
            raise RuntimeError('history pause not reached')
        if child.poll() is not None: raise RuntimeError('reservation process exited')
        child.kill()
        if child.wait(timeout=3) != -9: raise RuntimeError('reservation process not killed')
    finally:
        if child.poll() is None: child.kill(); child.wait(timeout=3)
        child.stdout.close(); child.stderr.close()
    if not (q / 'queue.reservation.previous').is_file() or (q / 'queue.reservation.settled').exists():
        raise RuntimeError('unexpected history state')
    op('front', expected=1, text='fifo/reservation-unknown')
    op('reserve', two, expected=1, text='fifo/reservation-unknown')
    op('settle-recover', two, expected=1, text='fifo/reservation-unknown')
    op('settle-recover', one, text='fifo/settled')
    op('front', text='fifo/front ' + two)
    op('reserve', two, text='fifo/reserved')
    if fd != 3: os.close(fd)
    os.close(3)
    print('PASS history rename crash: closed until matching settle recovery; next reservation succeeds')
