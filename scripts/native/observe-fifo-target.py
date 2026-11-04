#!/usr/bin/env python3
"""Observe a pinned native FIFO on disposable files; not a host governor."""
import argparse
import hashlib
import json
import os
import platform
import re
import select
import subprocess
import tempfile
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--fifo', required=True, type=Path)
    p.add_argument('--sha256', required=True)
    p.add_argument('--manifest-sha256', required=True)
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
    manifest_path = image.parent / 'manifest.json'
    if not re.fullmatch('[0-9a-f]{64}', a.manifest_sha256) or manifest_path.is_symlink():
        raise ValueError('bad manifest')
    raw_manifest = manifest_path.read_bytes()
    if hashlib.sha256(raw_manifest).hexdigest() != a.manifest_sha256: raise ValueError('manifest mismatch')
    manifest = json.loads(raw_manifest)
    phases = ('after-intent', 'after-pending', 'after-rename', 'after-sync', 'after-archive')
    if manifest.get('fifoSha256') != a.sha256 or set(manifest.get('instrumented', {})) != set(phases):
        raise ValueError('manifest content mismatch')
    variants = {}
    for phase in phases:
        path = image.parent / (phase + '.macho'); pin = manifest['instrumented'][phase]
        if path.is_symlink() or not re.fullmatch('[0-9a-f]{64}', pin) or hashlib.sha256(path.read_bytes()).hexdigest() != pin:
            raise ValueError('variant mismatch: ' + phase)
        subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(path)], check=True, capture_output=True, timeout=30)
        variants[phase] = path
    with tempfile.TemporaryDirectory(prefix='awai151-fifo-target-') as tmp:
        root = Path(tmp); q = root / 'case'; q.mkdir(mode=0o700)
        (q/'queue.lock').write_text(''); (q/'queue').write_text('RGQ2\n')
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
            expect('reserve', (0, 'fifo/reserved'), x)
            expect('front', (1, 'fifo/reservation-held'))
            expect('dequeue', (1, 'fifo/reservation-held'), x)
            expect('release', (1, 'fifo/reservation-unknown'), y)
            expect('release', (0, 'fifo/released'), x)
            expect('front', (0, 'fifo/front '+x))
            expect('dequeue', (1, 'fifo/not-front'), y)
            expect('dequeue', (0, 'fifo/dequeued'), x)
            expect('front', (0, 'fifo/front '+y))
            (q/'queue.pending').write_text('partial')
            expect('front', (1, 'fifo/pending-unknown'))
            (q/'queue.pending').unlink()
            old, new = 'RGQ2\n', 'RGQ2\n'+x+'\n'
            (q/'queue').write_text(old); (q/'queue.intent').write_text('RGI1\n'+old+'NEXT\n'+new)
            expect('recover', (0, 'fifo/aborted'))
            if (q/'queue').read_text() != old: raise ValueError('old state changed')
            (q/'queue').write_text(new); (q/'queue.intent').write_text('RGI1\n'+old+'NEXT\n'+new)
            expect('recover', (0, 'fifo/recovered'))
            if (q/'queue').read_text() != new: raise ValueError('new state changed')
            killed = []
            for phase, variant in variants.items():
                case = root / ('kill-' + phase); case.mkdir(mode=0o700)
                (case/'queue.lock').write_text(''); (case/'queue').write_text(old)
                child = subprocess.Popen([str(variant), '/kill-' + phase, 'enqueue', x], cwd=root,
                                         pass_fds=(3,), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                         env={'PATH':'/nonexistent'})
                try:
                    if not select.select([child.stdout], [], [], 10)[0] or os.read(child.stdout.fileno(), 1) != b'\0':
                        raise RuntimeError('pause not reached: ' + phase)
                    if child.poll() is not None: raise RuntimeError('child exited before kill')
                    expected_new = phase in ('after-rename', 'after-sync', 'after-archive')
                    if ((case/'queue').read_text() != (new if expected_new else old) or
                            (case/'queue.intent').exists() != (phase != 'after-archive')):
                        raise ValueError('stopped state mismatch: ' + phase)
                    child.kill()
                    if child.wait(timeout=3) != -9: raise ValueError('child kill status mismatch')
                    def observe(action):
                        r = subprocess.run([str(image), '/kill-' + phase, action], cwd=root, pass_fds=(3,),
                                           capture_output=True, timeout=15, env={'PATH':'/nonexistent'})
                        if r.stderr: raise ValueError('unexpected stderr')
                        return r.returncode, r.stdout.decode().strip()
                    expected_front = (0, 'fifo/front '+x) if phase == 'after-archive' else (1, 'fifo/pending-unknown')
                    if observe('front') != expected_front: raise ValueError('post-kill front mismatch: ' + phase)
                    expected_recovery = {'after-intent': (0, 'fifo/aborted'),
                        'after-pending': (1, 'fifo/recovery-unresolved'),
                        'after-rename': (0, 'fifo/recovered'), 'after-sync': (0, 'fifo/recovered'),
                        'after-archive': (1, 'fifo/no-intent')}[phase]
                    if observe('recover') != expected_recovery: raise ValueError('recovery mismatch: ' + phase)
                    if (case/'queue').read_text() != (new if expected_new else old): raise ValueError('queue changed')
                    killed.append(phase)
                finally:
                    if child.poll() is None: child.kill(); child.wait(timeout=3)
        finally:
            if fd != 3:
                if saved is None: os.close(3)
                else: os.dup2(saved, 3); os.close(saved)
            os.close(fd)
    print(json.dumps({'build':build, 'fifoSha256':a.sha256, 'contractPassed':True, 'reservationPassed':True,
                      'manifestSha256':a.manifest_sha256, 'syntheticRecoveryPassed':True,
                      'ownedChildKillPhases':killed, 'governorQualified':False}))


if __name__ == '__main__': main()
