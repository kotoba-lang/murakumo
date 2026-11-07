"""Test-only probe of an existing Darwin FS2 string-response diagnostic.

This does not exercise the checkpoint candidate or its bytes-response ABI.
The diagnostic image is supplied externally and pinned below; no build occurs.
"""
import argparse
import hashlib
import os
from pathlib import Path
import resource
import signal
import subprocess
import tempfile


FIXTURE_SHA256 = "4bf0268cb855cd11ade05ef7ccf2b6ebcdac0b12d934568dcb52fbac970939de"


def limits():
    resource.setrlimit(resource.RLIMIT_CPU, (4, 4))


def main(fixture):
    assert hashlib.sha256(fixture.read_bytes()).hexdigest() == FIXTURE_SHA256
    with tempfile.TemporaryDirectory(prefix="awai-fs2-empty-") as directory:
        root = Path(directory)
        (root / "empty").write_bytes(b"")
        (root / "one").write_bytes(b"x")
        data = bytes(65 + (i * 17) % 26 for i in range(4097))
        (root / "boundary").write_bytes(data)
        fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
        saved = None
        if fd != 3:
            try:
                saved = os.dup(3)
            except OSError:
                pass
            os.dup2(fd, 3)
        try:
            for name, tail in (("empty", "0:0"), ("one", "0:0")):
                r = subprocess.run([str(fixture), "E", "/" + name, tail], cwd=root, pass_fds=(3,),
                                   capture_output=True, timeout=4, preexec_fn=limits)
                assert r.returncode == 0 and r.stdout == b"\x00FS2\x00\n" and r.stderr == b""
            for name, tail in (("missing", "0:0"), ("empty", "0:1")):
                r = subprocess.run([str(fixture), "E", "/" + name, tail], cwd=root, pass_fds=(3,),
                                   capture_output=True, timeout=4, preexec_fn=limits)
                assert r.returncode == -signal.SIGTRAP and r.stdout == b"" and r.stderr == b""
            for tail, expected in (("0:4096", data[:4096]), ("4096:1", data[4096:]),
                                   ("100:3997", data[100:])):
                r = subprocess.run([str(fixture), "E", "/boundary", tail], cwd=root, pass_fds=(3,),
                                   capture_output=True, timeout=4, preexec_fn=limits)
                assert r.returncode == 0 and r.stdout == b"\x00FS2\x00" + expected + b"\n" and r.stderr == b""
            r = subprocess.run([str(fixture), "E", "/boundary", "100:4096"], cwd=root, pass_fds=(3,),
                               capture_output=True, timeout=4, preexec_fn=limits)
            assert r.returncode == -signal.SIGTRAP and r.stdout == b"" and r.stderr == b""
            print("PASS diagnostic string response: zero and bounded ranges; overrun traps")
        finally:
            if fd != 3:
                if saved is None:
                    os.close(3)
                else:
                    os.dup2(saved, 3)
                    os.close(saved)
            os.close(fd)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", type=Path, required=True)
    main(parser.parse_args().fixture.resolve())
