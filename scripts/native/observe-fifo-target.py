#!/usr/bin/env python3
"""Observe a pinned native FIFO on disposable files; not a host governor."""
import argparse
import hashlib
import json
import os
import platform
import re
import subprocess
import tempfile
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--fifo', required=True, type=Path)
    p.add_argument('--sha256', required=True)
    a = p.parse_args()
    if not re.fullmatch('[0-9a-f]{64}', a.sha256) or a.fifo.is_symlink(): raise ValueError('bad pin or path')
    image = a.fifo.resolve(strict=True)
    if not image.is_file() or hashlib.sha256(image.read_bytes()).hexdigest() != a.sha256:
        raise ValueError('image mismatch')
    if (platform.system(), platform.machine()) != ('Darwin', 'arm64'): raise ValueError('Mac ARM64 required')
    build = subprocess.run(['/usr/bin/sw_vers', '-buildVersion'], check=True, capture_output=True,
                           text=True, timeout=10).stdout.strip()
    if build != '25G332': raise ValueError('unexpected Mac build')
    subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(image)], check=True, capture_output=True, timeout=30)
    with tempfile.TemporaryDirectory(prefix='awai151-fifo-target-') as tmp:
        root = Path(tmp); q = root / 'case'; q.mkdir(mode=0o700)
        (q/'queue.lock').write_text(''); (q/'queue').write_text('RGQ1\n')
        fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY); saved = None
        if fd != 3:
            try: saved = os.dup(3)
            except OSError: pass
            os.dup2(fd, 3)
        try:
            def expect(action, expected, ticket=None):
                args = [str(image), '/case', action] + ([ticket] if ticket else [])
                r = subprocess.run(args, cwd=root, pass_fds=(3,), capture_output=True, timeout=15,
                                   env={'PATH':'/nonexistent'})
                if r.stderr or (r.returncode, r.stdout.decode().strip()) != expected:
                    raise RuntimeError((action, r.returncode, r.stdout, r.stderr))
            x, y = 'a'*64, 'b'*64
            expect('front', (0, 'fifo/empty'))
            expect('enqueue', (0, 'fifo/enqueued'), x)
            expect('enqueue', (0, 'fifo/enqueued'), y)
            expect('front', (0, 'fifo/front '+x))
            expect('dequeue', (1, 'fifo/not-front'), y)
            expect('dequeue', (0, 'fifo/dequeued'), x)
            expect('front', (0, 'fifo/front '+y))
            (q/'queue.pending').write_text('partial')
            expect('front', (1, 'fifo/pending-unknown'))
            (q/'queue.pending').unlink()
            old, new = 'RGQ1\n', 'RGQ1\n'+x+'\n'
            (q/'queue').write_text(old); (q/'queue.intent').write_text('RGI1\n'+old+'NEXT\n'+new)
            expect('recover', (0, 'fifo/aborted'))
            if (q/'queue').read_text() != old: raise ValueError('old state changed')
            (q/'queue').write_text(new); (q/'queue.intent').write_text('RGI1\n'+old+'NEXT\n'+new)
            expect('recover', (0, 'fifo/recovered'))
            if (q/'queue').read_text() != new: raise ValueError('new state changed')
        finally:
            if fd != 3:
                if saved is None: os.close(3)
                else: os.dup2(saved, 3); os.close(saved)
            os.close(fd)
    print(json.dumps({'build':build, 'fifoSha256':a.sha256, 'contractPassed':True,
                      'syntheticRecoveryPassed':True, 'governorQualified':False}))


if __name__ == '__main__': main()
