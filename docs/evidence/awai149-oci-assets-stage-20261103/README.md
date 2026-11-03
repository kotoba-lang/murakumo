# Restricted model assets staging candidate

`model-assets-stage-main SOURCE NEW_DIRECTORY SHA256 SIZE` stages a pinned,
uncompressed USTAR archive under an exclusive host-owned filesystem scope. It
creates a new private directory and regular files, streams and hashes the
actual header, data and padding bytes as it writes, and reports success only
after the archive digest and metadata match and files and directory sync.
The caller must obtain the exact asset digest and size from an independently
verified OCI manifest. This entry is not yet wired into `model-artifact-main`.

The current version-specific proposal limits the archive to 64 MiB and each
file to 16 MiB. It accepts only the 15 fixed flat names seen in the prior
Bonsai directory inventory, requiring the first eight: config, tokenizer and
tokenizer config, chat template, Hadamard and MTPLX runtime settings, model
index and pack manifest. Entries must be regular USTAR files with valid header
checksums, no link target or prefix, unique allowed names and zero padding;
PAX/GNU extensions, nested paths, symlinks, hardlinks, devices and unknown
names are refused. Files are set to mode 0600 and the new directory to 0700.
This restricted list is not proof of the actual runtime dependency closure.

On a local Darwin ARM64 Mac, a native source-unity fixture with the diagnostic
FS2 provider staged synthetic USTAR data including a 130,007-byte tokenizer
that crossed the 64 KiB write boundary. Every staged byte and mode matched.
It refused an existing directory, wrong digest, missing required file, duplicate
or unknown name, traversal, symlink, invalid checksum, nonzero padding and
trailing data, a trailing-slash destination and an oversized sparse input.
Malformed and failed cases can leave an incomplete private directory, which
must be treated as unknown and reconciled explicitly; they are not activated.

The test does not qualify crash/power-loss durability, source/parent races,
malicious same-user access, the FS2 provider, real model assets, registry,
model loader or governor. In particular, the host must prevent concurrent
mutation and must not let a consumer access a partial directory; a crash or
lost response does not imply success. There is no durable completion receipt,
atomic publication or model launch in this candidate.

| Artifact | SHA-256 |
| --- | --- |
| staging source | `4b46b0ece8d11ac6b47e4468cc956b7473578629cae7b0c5330839af123abd28` |
| test unity | `da1989dee4e8b4373bad267374c33ab65f80bca927d97ec806ac588c431c9a03` |
| signed diagnostic fixture | `3d82f62a45fcceef4c7cf883074dc62c6b2f4167e53cc79ccf0909f19dfa98a7` |
