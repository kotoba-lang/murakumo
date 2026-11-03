# Streaming model digest candidate

The Bonsai MTP main weight file previously observed on three minis is about
8.6 GB, beyond the existing 4 MiB OCI cell-layer whole-read profile. The new
`fleet_model_digest.kotoba` is a separate bounded streaming SHA-256 observer:
given a host-pinned path, expected `sha256:` digest and exact size, it checks
FS2 metadata and reads at most 64 KiB per request, compressing SHA-256 blocks
without keeping the whole file in memory. It refuses sizes above 64 GiB, bad
inputs, failed/short reads, changed metadata, and digest mismatches. A match
returns `model/digest-observed`, not an execution grant.

On one local Darwin ARM64 Mac, a test-only native source-unity fixture used
the actual observer, SHA-256 core, metadata parsing and a diagnostic FS2 host.
For byte sequences of 0, 1, 55, 56, 63, 64, 65, 1,024, 65,535, 65,536 and
65,537 bytes, the observer agreed with Python's independent `hashlib` digest.
Wrong digest and size refused. A separate injected short-read fixture returned
`model/read-unknown`. Traversal, noncanonical size and a missing path were
also refused. The digest-descriptor parser was stubbed in these fixtures.

The diagnostic FS2 host trapped when asked for metadata on a directory, so
its error behavior is not production-qualified. The native observer assumes a
host-owned private scope and no writers, links or path replacement. FS2 range
reads reopen by path; matching metadata and digest do not seal the file or
prevent a change between verification and loading. This does not parse an OCI
manifest, perform registry download, qualify source provenance or authorize a
model launch. No large model was read, deployed or used for inference: the
8.6 GB path, long-run performance, bounded resource consumption and model
identity remain to be verified under the qualified governor.

| Artifact | SHA-256 |
| --- | --- |
| observer source | `8d970bdce71bd036cbe722927c7b0355d30776479aef623f5cbca6aed17c1b2d` |
| diagnostic fixture | `8dc6289c86fbb6a75ab30b2253ab4efff86991c486ff934e94e465174046f9c8` |
| signed fixture | `57b22d95eee0dd2aca295b8f9d56668193c666ebfb8a7f5d6fd3787ecf747a65` |
| injected short-read fixture | `d9522c3cf4ec3b4bac49996430c1a37ba1e00313a29b03d45204cb59ca32aa72` |
