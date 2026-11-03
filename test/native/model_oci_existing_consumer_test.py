"""Test a synthetic producer-format layout with a pinned existing Mac consumer.

The fixture is a previously built diagnostic, not the new OCI seal component.
No model, registry, build, deployment, or production host is used.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import resource
import subprocess
import tarfile
import tempfile


FIXTURE_SHA256 = "d5a852ecc05069d9fc93e044714a369c0a665d9b2c6db17c0a665b97155120c1"
IMAGE = "application/vnd.oci.image.manifest.v1+json"
NAMES = ("config.json", "tokenizer.json", "tokenizer_config.json", "chat_template.jinja",
         "hadamard.json", "mtplx_runtime.json", "model.safetensors.index.json",
         "MTPLX_PACK_MANIFEST.json")


def digest(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


def descriptor(media, data):
    return {"digest": digest(data), "mediaType": media, "size": len(data)}


def limits():
    resource.setrlimit(resource.RLIMIT_CPU, (10, 10))


def main(fixture):
    assert hashlib.sha256(fixture.read_bytes()).hexdigest() == FIXTURE_SHA256
    assets = io.BytesIO()
    payloads = {name: (name + " synthetic fixture\n").encode() for name in NAMES}
    with tarfile.open(fileobj=assets, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for name, data in payloads.items():
            info = tarfile.TarInfo(name)
            info.size, info.mtime = len(data), 0
            archive.addfile(info, io.BytesIO(data))
    base, mtp, tar = b"synthetic-base\n", b"synthetic-mtp\n", assets.getvalue()
    manifest = {
        "artifactType": "application/vnd.awai.model.bonsai.v1",
        "config": descriptor("application/vnd.oci.empty.v1+json", b"{}"),
        "layers": [descriptor("application/vnd.awai.model.bonsai.weights.v1", base),
                   descriptor("application/vnd.awai.model.bonsai.mtp.v1", mtp),
                   descriptor("application/vnd.awai.model.bonsai.assets.tar.v1", tar)],
        "mediaType": IMAGE, "schemaVersion": 2,
    }
    encoded = json.dumps(manifest, separators=(",", ":")).encode()
    index = json.dumps({"manifests": [descriptor(IMAGE, encoded)], "schemaVersion": 2},
                       separators=(",", ":")).encode()
    with tempfile.TemporaryDirectory(prefix="awai149-oci-consumer-") as directory:
        root = Path(directory)
        layout = root / "layout"
        blobs = layout / "blobs" / "sha256"
        blobs.mkdir(parents=True)
        for data in (b"{}", base, mtp, tar, encoded):
            (blobs / digest(data)[7:]).write_bytes(data)
        (layout / "oci-layout").write_bytes(b'{"imageLayoutVersion":"1.0.0"}')
        (layout / "index.json").write_bytes(index)
        fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
        saved = None
        if fd != 3:
            try:
                saved = os.dup(3)
            except OSError:
                pass
            os.dup2(fd, 3)
        try:
            args = ["/layout", digest(encoded), str(len(encoded)), digest(base), str(len(base)),
                    digest(mtp), str(len(mtp)), digest(tar), str(len(tar))]

            def call(arguments):
                return subprocess.run([str(fixture), *arguments], cwd=root, pass_fds=(3,),
                                      capture_output=True, text=True, timeout=20, preexec_fn=limits)

            wrong = args.copy()
            wrong[3] = "sha256:" + "0" * 64
            rejected = call([*wrong, "/badstage", "/badtarget"])
            assert rejected.returncode != 0 and rejected.stdout.strip() == "model-artifact/descriptor-refused"
            assert not (root / "badtarget").exists()
            accepted = call([*args, "/stage", "/published"])
            assert accepted.returncode == 0 and accepted.stdout.strip() == "model-artifact/published"
            assert (root / "published/model.safetensors").read_bytes() == base
            assert (root / "published/mtp.safetensors").read_bytes() == mtp
            for name, data in payloads.items():
                assert (root / "published" / name).read_bytes() == data
            print("PASS existing native consumer: valid layout and wrong pin")
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
