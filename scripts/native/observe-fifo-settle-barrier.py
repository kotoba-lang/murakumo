#!/usr/bin/env python3
"""Observe a task-owned RGQ4 settle barrier on one Darwin ARM64 host.

This executes signed diagnostic images, not a deployed resource governor.
"""
import argparse
import hashlib
import os
import platform
import resource
import subprocess
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser()
for name in ('good', 'lost', 'bypass'):
    parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--' + name + '-sha256', required=True)
a = parser.parse_args()
assert (platform.system(), platform.machine()) == ('Darwin', 'arm64')
images = {}
for name in ('good', 'lost', 'bypass'):
    p = getattr(a, name).resolve(strict=True)
    assert hashlib.sha256(p.read_bytes()).hexdigest() == getattr(a, name + '_sha256')
    subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(p)], check=True, capture_output=True, timeout=10)
    images[name] = p

def limit():
    resource.setrlimit(resource.RLIMIT_CPU, (3, 3))

with tempfile.TemporaryDirectory(prefix='awai-rgq4-target-') as tmp:
    root = Path(tmp); q = root / 'q'; q.mkdir(mode=0o700)
    (q / 'queue.lock').write_text(''); one, two = 'a' * 64, 'b' * 64
    (q / 'queue').write_text('RGQ4\n' + one + '\n' + two + '\n')
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    if fd != 3: os.dup2(fd, 3)
    def op(action, ticket='', image='good', expected=0, text=''):
        cmd = [str(images[image]), '/q', action] + ([ticket] if ticket else [])
        r = subprocess.run(cmd, cwd=root, pass_fds=(3,), env={'PATH': '/nonexistent'},
                           capture_output=True, timeout=12, preexec_fn=limit)
        assert (r.returncode, r.stdout.decode().strip(), r.stderr) == (expected, text, b''), action
    op('reserve', one, text='fifo/reserved')
    op('release', one, text='fifo/release-pending')
    op('dequeue', one, text='fifo/dequeued')
    op('settle', one, image='lost', expected=1, text='fifo/reservation-unknown')
    op('front', expected=1, text='fifo/reservation-unknown')
    op('front', image='bypass', text='fifo/front ' + two)
    op('settle-recover', two, expected=1, text='fifo/reservation-unknown')
    op('settle-recover', one, text='fifo/settled')
    op('front', text='fifo/front ' + two)
    op('reserve', two, text='fifo/reserved')
    op('release', two, text='fifo/release-pending')
    op('dequeue', two, text='fifo/dequeued')
    op('settle', two, text='fifo/settled')
    op('front', text='fifo/empty')
    assert (q / 'queue.reservation.settled').exists()
    old = root / 'old'; old.mkdir(); (old / 'queue.lock').write_text(''); (old / 'queue').write_text('RGQ3\n')
    r = subprocess.run([str(images['good']), '/old', 'front'], cwd=root, pass_fds=(3,),
                       env={'PATH': '/nonexistent'}, capture_output=True, timeout=12, preexec_fn=limit)
    assert (r.returncode, r.stdout, r.stderr) == (1, b'fifo/state-invalid\n', b'')
    if fd != 3: os.close(fd)
    os.close(3)
    print('PASS', platform.release(), 'settle-loss barrier, bypass control, recovery, next turn, RGQ3 refusal')
