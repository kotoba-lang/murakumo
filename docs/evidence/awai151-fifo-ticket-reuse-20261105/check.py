#!/usr/bin/env python3
"""Observe ticket reuse using an isolated queue and a pinned diagnostic FIFO."""
import hashlib
import os
from pathlib import Path
import resource
import subprocess
import tempfile

image = Path('/private/tmp/awai151-current-fifo.macho')
assert hashlib.sha256(image.read_bytes()).hexdigest() == 'e0686d9f82fc3eb5f218a0e716d373ef3a0a9565b0e09f39bef4ce48e0ae9da7'
subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(image)], check=True, capture_output=True)

def limit():
    resource.setrlimit(resource.RLIMIT_CPU, (3, 3))

with tempfile.TemporaryDirectory(prefix='awai151-aba-', dir='/private/tmp') as directory:
    root = Path(directory)
    queue = root / 'q'
    queue.mkdir()
    (queue / 'queue').write_text('RGQ4\n')
    (queue / 'queue.lock').write_text('')
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    if fd != 3:
        os.dup2(fd, 3)
    ticket = 'a' * 64
    sequence = [('enqueue', 'fifo/enqueued'), ('reserve', 'fifo/reserved'),
                ('release', 'fifo/release-pending'), ('dequeue', 'fifo/dequeued'),
                ('settle', 'fifo/settled'), ('enqueue', 'fifo/enqueued'),
                ('reserve', 'fifo/reserved'), ('release', 'fifo/release-pending')]
    try:
        for operation, expected in sequence:
            result = subprocess.run([str(image), '/q', operation, ticket], cwd=directory,
                                    pass_fds=(3,), env={'PATH': '/nonexistent'},
                                    preexec_fn=limit, capture_output=True, timeout=8)
            actual = result.stdout.decode().strip()
            print(operation, result.returncode, actual, flush=True)
            if result.returncode != 0 or actual != expected or result.stderr:
                raise RuntimeError('unexpected FIFO result')
    finally:
        os.close(3)
        if fd != 3:
            os.close(fd)
print('temporary_directory_removed', not root.exists())
