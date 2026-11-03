# OCI model weights and assets staging candidate

This is the staging-only diagnostic. The later [publication candidate](../awai149-oci-publication-20261103/README.md)
adds a distinct journaled no-replace publication entry.

The combined OCI entry now stages the two weight blobs as
`model.safetensors` and `mtp.safetensors` alongside restricted assets. It
validates the pinned index, manifest, descriptors, small config and assets
digest first, and checks weight metadata. It then creates a private stage,
validates and extracts the assets tar, and reads each weight in bounded
64 KiB ranges. Each input chunk is hashed and written from the same buffer;
the pinned digest, source metadata and file sync must all succeed before the
stage directory is synced and `model-artifact/staged` is returned. The large
weight files are not separately hashed before copying, avoiding a second
full pass. The standalone observation entry still hashes without staging.

On one Darwin ARM64 Mac, a native source-unity fixture with the diagnostic
extended-range FS2 host staged an ORAS-created synthetic artifact. The base
was 131,073 bytes, MTP was 65,537 bytes and the assets included a
130,007-byte tokenizer fixture. All staged contents and file modes matched.
A test-only instrumented build observed exactly three base range reads and
two MTP range reads. Corrupt base or MTP, a short weight read, invalid asset
tar, incorrect descriptor pin, overlapping paths and an existing target were
refused. Failures after creation leave an unknown private stage; they must not
be consumed or retried without reconciliation. The reader-only OCI corpus and
standalone restricted-tar corpus were also rerun with current source.

These tests use synthetic files and a diagnostic provider. No real model,
registry, deploy, GPU inference or remote host was used. The entry is not an
immutable snapshot or atomic publication: the host must own and isolate both
trees from writers/aliases and prevent access to partial stages. There is no
durable success receipt or crash recovery, and filesystem semantics, free disk,
full model size/performance, publisher identity, safe runtime loading and
resource admission remain unqualified. Actual model staging is high load and
must run through the qualified governor. The model/profile media types and
required assets are still experimental.

| Artifact | SHA-256 |
| --- | --- |
| model reader source | `a061473887da6ef4d41c0466d4d49230d0681a98bd8663dd497a451ad2a9f938` |
| staging source | `ab4829da41d586104eb5c23bfd3f5f223659189503190df13417a926318ace3c` |
| native fixture source | `9e0cfb00213534c703e21344a1efa326d145d8adab931fa10067040eeaf59df9` |
| instrumented fixture source | `09a162f9e35407b35aee7e1abaa6eeccfbb35904db68b3f9b209bf5ae28bfb01` |
| signed native fixture | `082e46685b9a2151753509ccb5f7ec27f95fff06c7db8ef852ace156745f56c8` |
