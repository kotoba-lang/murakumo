#!/usr/bin/env python3
"""Run the source-bound FIFO boundary cases on an explicit diagnostic Mac runtime.

Python stages files and observes native compiler/packager/programs. This does
not qualify a provider, a crash-safe host, resource reservations or a governor.
"""
import argparse
import hashlib
import json
import os
import platform
import re
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
    a = p.parse_args()
    if (platform.system(), platform.machine()) != ('Darwin', 'arm64'): raise ValueError('Mac ARM64 required')
    compiler, packager = verify(a.compiler, a.compiler_sha256), verify(a.packager, a.packager_sha256)
    raw_component, raw_cases = COMPONENT.read_bytes(), CASES.read_bytes()
    component, cases = raw_component.decode(), raw_cases.decode()
    if component.count(ANCHOR) != 1: raise ValueError('negative control anchor changed')
    component = component[component.index('(defn- rq-arg'):]
    cases = cases[cases.index('(defn test-fifo-meta-size'):]
    unity = ('(ns fifo-boundary-fixture (:export [main]))\n' + component + '\n' + cases +
             '\n(defn main [] :i64 (if (and (test-fifo-meta-size) (test-fifo-bounded-reads "/cases")) 0 1))\n')
    with tempfile.TemporaryDirectory(prefix='awai151-fifo-boundary-', dir='/private/tmp') as tmp:
        root = Path(tmp); scope = root / 'cases'; scope.mkdir(mode=0o700)
        for name, data in [('small', b'RGQ1\n'), ('queue-limit', b'x'*4165), ('queue-over', b'x'*4166),
                           ('journal-limit', b'x'*8340), ('journal-over', b'x'*8341), ('empty', b'')]:
            (scope / name).write_bytes(data)
        (root / 'fixture.kotoba').write_text(unity)
        (root / 'mutant.kotoba').write_text(unity.replace(ANCHOR, 'false'))
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
            if COMPONENT.read_bytes() != raw_component or CASES.read_bytes() != raw_cases:
                raise ValueError('source changed during run')
            if sha(compiler.read_bytes()) != a.compiler_sha256 or sha(packager.read_bytes()) != a.packager_sha256:
                raise ValueError('tool changed during run')
            print(json.dumps({'componentSha256':sha(raw_component), 'casesSha256':sha(raw_cases),
                              'fixtureSha256':sha(unity.encode()), 'compilerSha256':a.compiler_sha256,
                              'packagerSha256':a.packager_sha256, 'positiveExit':0, 'boundRemovalMutantExit':1,
                              'scope':'diagnostic bounded reads; not provider or governor qualification'}))
        finally:
            if fd != 3:
                if saved is None: os.close(3)
                else: os.dup2(saved, 3); os.close(saved)
            os.close(fd)


if __name__ == '__main__': main()
