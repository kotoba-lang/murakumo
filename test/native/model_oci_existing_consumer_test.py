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
         "MTPLX_PACK_MANIFEST.json", "generation_config.json", "preprocessor_config.json",
         "processor_config.json", "video_preprocessor_config.json", "LICENSE", "NOTICE.txt",
         "README.md")


def digest(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


def descriptor(media, data):
    return {"digest": digest(data), "mediaType": media, "size": len(data)}


def limits():
    resource.setrlimit(resource.RLIMIT_CPU, (10, 10))


def ustar_header(name, size):
    """Independent USTAR byte-layout oracle for the candidate header format."""
    header = bytearray(512)
    encoded = name.encode("ascii")
    assert 0 < len(encoded) < 100 and 0 < size <= 16777216
    header[:len(encoded)] = encoded

    def octal(start, width, value):
        header[start:start + width - 1] = f"{value:0{width - 1}o}".encode("ascii")

    octal(100, 8, 0o600)
    octal(108, 8, 0)
    octal(116, 8, 0)
    octal(124, 12, size)
    octal(136, 12, 0)
    header[156] = ord("0")
    header[257:262], header[263:265] = b"ustar", b"00"
    checksum = sum(header[:148]) + 8 * 32 + sum(header[156:])
    octal(148, 7, checksum)
    header[155] = 32
    return bytes(header)


def main(fixture):
    assert hashlib.sha256(fixture.read_bytes()).hexdigest() == FIXTURE_SHA256
    assert hashlib.sha256(ustar_header("config.json", 123)).hexdigest() == (
        "a887e03f951d25a686a71789ad60df8e4e7bc0a0d6600a96bc9519f5ad947658")
    payloads = {name: (name + " synthetic fixture\n").encode() for name in NAMES}
    tar = b"".join(ustar_header(name, len(data)) + data + bytes((-len(data)) % 512)
                   for name, data in payloads.items()) + bytes(1024)
    with tarfile.open(fileobj=io.BytesIO(tar), mode="r:") as archive:
        assert {member.name: archive.extractfile(member).read() for member in archive.getmembers()} == payloads
    base, mtp = b"synthetic-base\n", b"synthetic-mtp\n"
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
            # Recompute all OCI descriptors around an archive containing an
            # unknown member: the consumer must enforce the asset inventory,
            # not merely reject an incorrect outer digest.
            extra = b"unlisted\n"
            bad_tar = tar[:-1024] + ustar_header("unexpected.json", len(extra)) + extra
            bad_tar += bytes((-len(extra)) % 512) + bytes(1024)
            with tarfile.open(fileobj=io.BytesIO(bad_tar), mode="r:") as archive:
                assert {member.name: archive.extractfile(member).read() for member in archive.getmembers()} == {
                    **payloads, "unexpected.json": extra}
            bad_manifest = dict(manifest)
            bad_manifest["layers"] = [*manifest["layers"][:2],
                                      descriptor("application/vnd.awai.model.bonsai.assets.tar.v1", bad_tar)]
            bad_encoded = json.dumps(bad_manifest, separators=(",", ":")).encode()
            bad_index = json.dumps({"manifests": [descriptor(IMAGE, bad_encoded)], "schemaVersion": 2},
                                   separators=(",", ":")).encode()
            bad_layout = root / "badlayout"
            bad_blobs = bad_layout / "blobs" / "sha256"
            bad_blobs.mkdir(parents=True)
            for data in (b"{}", base, mtp, bad_tar, bad_encoded):
                (bad_blobs / digest(data)[7:]).write_bytes(data)
            (bad_layout / "oci-layout").write_bytes(b'{"imageLayoutVersion":"1.0.0"}')
            (bad_layout / "index.json").write_bytes(bad_index)
            bad_args = ["/badlayout", digest(bad_encoded), str(len(bad_encoded)), digest(base), str(len(base)),
                        digest(mtp), str(len(mtp)), digest(bad_tar), str(len(bad_tar))]
            rejected_extra = call([*bad_args, "/extrastage", "/extratarget"])
            assert rejected_extra.returncode != 0 and rejected_extra.stdout.strip() == "model-artifact/publication-unknown"
            assert not (root / "extratarget").exists()
            print("PASS existing native consumer: 15 assets, wrong pin, unlisted asset")
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
