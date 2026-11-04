#!/usr/bin/env python3
"""Compare pinned stock and diagnostic Darwin FS2 behavior in a private scope."""
import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('ticket', ROOT / 'scripts/native/check-fifo-ticket-once.py')
ticket = importlib.util.module_from_spec(spec); spec.loader.exec_module(ticket)
PROBE = '''(ns fs2-t-probe (:export [main]))
(defn main [] :i64
  (let [op (typed-cap-call 38 :string :string "0")
        nul (string-from-utf8 (bytes-from-vector-i64 [0]))
        response (typed-cap-call 35 :string :string (str nul "FS2" nul op "/a" nul (if (= op "T") "/b" "")))
        sent (typed-cap-call 37 :string :string (str response "\\n"))] 0))
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('compiler', 'stock', 'diagnostic'):
        parser.add_argument('--' + name, type=Path, required=True)
        parser.add_argument('--' + name + '-sha256', required=True)
    args = parser.parse_args()
    compiler = ticket.pinned(args.compiler, args.compiler_sha256)
    stock = ticket.pinned(args.stock, args.stock_sha256)
    diagnostic = ticket.pinned(args.diagnostic, args.diagnostic_sha256)
    with tempfile.TemporaryDirectory(prefix='awai151-stock-provider-', dir='/private/tmp') as directory:
        root = Path(directory); fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
        if fd != 3:
            os.dup2(fd, 3)
        def run(command):
            return subprocess.run([str(x) for x in command], cwd=root, pass_fds=(3,),
                                  env={'PATH': '/nonexistent'}, capture_output=True,
                                  timeout=20, preexec_fn=ticket.limit)
        def success(command):
            result = run(command)
            if result.returncode or result.stderr:
                raise RuntimeError((command, result.returncode, result.stderr[-300:]))
            return result.stdout
        try:
            (root / 'probe.kotoba').write_text(PROBE)
            success([compiler, 'compile', 'probe.kotoba', '--target', 'aarch64-macos', '--output', 'probe.kseed'])
            output = success([compiler, 'extract-native', 'probe.kseed', '--symbol', 'main', '--output', 'probe.raw'])
            match = re.search(rb':offset (\d+), :length (\d+), :arity 0}', output)
            if not match or int(match[2]) != (root / 'probe.raw').stat().st_size:
                raise ValueError('native extraction mismatch')
            for name, packager in [('stock', stock), ('diagnostic', diagnostic)]:
                success([packager, name + '-unsigned.macho', 'probe.raw', 'fs2-cli-stdio-general', match[1].decode()])
                image = root / (name + '.macho'); shutil.copyfile(root / (name + '-unsigned.macho'), image); image.chmod(0o700)
                subprocess.run(['/usr/bin/codesign', '--force', '--sign', '-', str(image)], check=True, capture_output=True)
                subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(image)], check=True, capture_output=True)
                (root / 'a').write_text('source'); (root / 'b').write_text('target')
                check = run([image, 'D'])
                if check.returncode or check.stdout != b'\0FS2\x001\n' or check.stderr:
                    raise ValueError(('D control', name, check.returncode, check.stdout, check.stderr))
                check = run([image, 'O'])
                if name == 'stock':
                    if check.returncode != -5 or check.stdout or check.stderr:
                        raise ValueError(('stock O observation', check.returncode, check.stdout, check.stderr))
                else:
                    fields = check.stdout[5:].strip().split()
                    if check.returncode or check.stderr or not check.stdout.startswith(b'\0FS2\0') or len(fields) != 4 or fields[1] != b'6' or fields[3] != b'0':
                        raise ValueError(('diagnostic O observation', check.returncode, check.stdout, check.stderr))
                print(name, 'O exit', check.returncode, 'stdout', repr(check.stdout), flush=True)
                result = run([image, 'T'])
                expected = (-5, b'') if name == 'stock' else (0, b'\0FS2\x000\n')
                if (result.returncode, result.stdout) != expected or result.stderr:
                    raise ValueError(('T observation', name, result.returncode, result.stdout, result.stderr))
                if (root / 'a').read_text() != 'source' or (root / 'b').read_text() != 'target':
                    raise ValueError('rename changed a test file')
                print(name, 'T exit', result.returncode, 'stdout', repr(result.stdout), flush=True)
                print(name, 'signed_probe_sha256', hashlib.sha256(image.read_bytes()).hexdigest(), flush=True)
        finally:
            os.close(3)
            if fd != 3:
                os.close(fd)
    print('temporary_directory_removed', not root.exists())


if __name__ == '__main__':
    main()
