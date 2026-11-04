#!/usr/bin/env python3
"""Build and run the bounded checkpoint probe with explicitly pinned Mac tools.

Diagnostic only: the caller supplies a compiler and FS2-capable packager and
must qualify their provenance separately. Python stages and observes native
programs. This does not qualify a runtime, supervisor, GitHub or recovery.
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

HERE = Path(__file__).resolve().parent
COMPARE = '(= (vector-at part i) (vector-at b (+ start i)))'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verified(path, expected):
    if path.is_symlink() or not re.fullmatch('[0-9a-f]{64}', expected):
        raise ValueError('invalid input or pin')
    path = path.resolve(strict=True)
    if not path.is_file() or digest(path) != expected:
        raise ValueError('image pin mismatch')
    subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(path)],
                   check=True, capture_output=True, timeout=30)
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler', type=Path, required=True)
    parser.add_argument('--compiler-sha256', required=True)
    parser.add_argument('--packager', type=Path, required=True)
    parser.add_argument('--packager-sha256', required=True)
    args = parser.parse_args()
    if (platform.system(), platform.machine()) != ('Darwin', 'arm64'):
        raise ValueError('Apple Silicon macOS required')
    compiler = verified(args.compiler, args.compiler_sha256)
    packager = verified(args.packager, args.packager_sha256)
    with tempfile.TemporaryDirectory(prefix='awai150-bounded-write-') as tmp:
        root = Path(tmp)
        source = root / 'fixture.kotoba'
        gen = subprocess.run(['/usr/bin/python3', str(HERE / 'generate-checkpoint-bounded-fixture.py'),
                              '--output', str(source)], check=True, capture_output=True, text=True, timeout=30)
        receipt = json.loads(gen.stdout)
        original = source.read_text()
        if original.count(COMPARE) != 1:
            raise ValueError('negative control anchor changed')
        mutant = root / 'mutant.kotoba'
        mutant.write_text(original.replace(COMPARE, 'true'))
        fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
        saved = None
        if fd != 3:
            try: saved = os.dup(3)
            except OSError: pass
            os.dup2(fd, 3)
        try:
            def run(argv, expected, timeout=180):
                r = subprocess.run([str(x) for x in argv], cwd=root, pass_fds=(3,),
                                   capture_output=True, timeout=timeout, env={'PATH': '/nonexistent'})
                if r.returncode != expected or r.stderr:
                    raise RuntimeError((str(argv[0]), r.returncode, r.stdout[-300:], r.stderr[-300:]))
                return r.stdout
            def build(name):
                run([compiler, 'compile', name + '.kotoba', '--target', 'aarch64-macos',
                     '--output', name + '.kseed'], 0)
                output = run([compiler, 'extract-native', name + '.kseed', '--symbol', 'main',
                              '--output', name + '.raw'], 0)
                match = re.search(rb':offset (\d+), :length (\d+), :arity 0}', output)
                if not match or int(match.group(2)) != (root / (name + '.raw')).stat().st_size:
                    raise ValueError('extraction mismatch')
                run([packager, name + '-unsigned.macho', name + '.raw',
                     'fs2-cli-stdio-general', match.group(1).decode()], 0)
                image = root / (name + '.macho')
                shutil.copyfile(root / (name + '-unsigned.macho'), image)
                image.chmod(0o700)
                subprocess.run(['/usr/bin/codesign', '--force', '--sign', '-', str(image)],
                               check=True, capture_output=True, timeout=30)
                subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(image)],
                               check=True, capture_output=True, timeout=30)
                return image
            positive = build('fixture')
            negative = build('mutant')
            (root / 'positive').mkdir(mode=0o700)
            (root / 'negative').mkdir(mode=0o700)
            run([positive, '/positive'], 0)
            expected = bytes((i * 17 + 65) % 256 for i in range(4097))
            if (root / 'positive/empty').read_bytes() != b'' or (root / 'positive/full').read_bytes() != expected:
                raise ValueError('independent readback mismatch')
            run([negative, '/negative'], 1)
            if (root / 'negative/empty').read_bytes() != b'' or (root / 'negative/full').read_bytes() != expected:
                raise ValueError('negative control did not reach the comparison')
            if digest(compiler) != args.compiler_sha256 or digest(packager) != args.packager_sha256:
                raise ValueError('tool changed during execution')
            project = HERE.parents[1]
            if (digest(project / 'kotoba/git_checkpoint.kotoba') != receipt['componentSha256'] or
                    digest(project / 'test/native/checkpoint_bounded_write_cases.kotoba') != receipt['casesSha256']):
                raise ValueError('source changed during execution')
            receipt.update({'compilerSha256': args.compiler_sha256, 'packagerSha256': args.packager_sha256,
                            'positiveExit': 0, 'independentReadback': True, 'comparisonMutantExit': 1,
                            'scope': 'diagnostic FS2 owned temporary root; not production qualification'})
            print(json.dumps(receipt))
        finally:
            if fd != 3:
                if saved is None: os.close(3)
                else: os.dup2(saved, 3); os.close(saved)
            os.close(fd)


if __name__ == '__main__':
    main()
