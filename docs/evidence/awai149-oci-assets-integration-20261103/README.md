# OCI model verification and assets staging

This records the assets-only integration. The later [full staging candidate](../awai149-oci-full-stage-20261103/README.md)
also copies and hashes the base and MTP weights in the same stage.

`model-artifact-stage-main` combines the experimental model OCI reader and
restricted USTAR staging. The host pins the manifest and all three layer
digests/sizes and provides a fresh private stage outside the OCI layout. The
entry refuses bad input, overlapping or existing destinations before reading
the artifact. It validates the layout, index, manifest, config and all three
layer digests before creating a stage; it then hashes the asset tar again
while parsing and staging its actual contents. Failure during staging can leave
a partial private directory and must not be treated as success or reused.

On one Darwin ARM64 Mac, ORAS 1.3.0 created local synthetic OCI layouts. A
source-unity native fixture used the actual OCI parser, descriptor validator,
SHA-256, restricted USTAR staging and an extended-range diagnostic FS2 host.
The valid artifact staged eight required files, including a 130,007-byte
tokenizer fixture, with correct bytes and modes. Wrong pins, a corrupted base,
a corrupted assets blob and an overlapping destination were refused before
creating a stage. A validly pinned tar missing a required file left an unknown
partial stage; an existing destination was refused. The standalone staging
entry's earlier negative corpus was rerun after refactoring. No registry,
real model, inference, or remote host was involved.

This connects assets staging only: base and MTP weights remain in the OCI
layout, are not installed, and can change after observation. FS2 path reads
do not produce a sealed snapshot; the host must exclude writers and aliases,
protect stages from consumers until success, and reconcile interrupted work.
There is no durable completion receipt, atomic publication, publisher identity,
production provider qualification, model loader, resource admission or deploy.
The full weight hash may be high load and must run through the qualified
governor; this test does not qualify that operation or the required assets list.

| Artifact | SHA-256 |
| --- | --- |
| staging source | `614fae3816b443d8e4f6b86910d1a8599f4179386bcfc487db7a0540fe217475` |
| model reader source | `f27fe3316227d96bf1240059ec85f88dcb9f090ce252f7bc0257831b0e78109c` |
| test unity source | `34213a53f352188d5b49b763a72761c6bc541acbd70fd214a4656e363355af89` |
| signed diagnostic fixture | `fdbadf3bd1925a5aa01d3b66b394bcd3bb1b884102ff3b6e9eeb44da97d6c8eb` |
