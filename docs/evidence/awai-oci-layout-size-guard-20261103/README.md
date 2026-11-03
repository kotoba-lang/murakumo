# Refuse large descriptors in the whole-read OCI verifier

`fleet_oci_layout` reads each descriptor's blob into a byte value and then a
`vector-i64` before hashing. That path is not suitable for an 8.6 GB model.
It now refuses declared blobs over 4 MiB before requesting their existence or
contents, with `layout/blob-too-large`. This is an intentionally restrictive
compatibility change: larger images that previously reached the reader will
now be refused. The threshold is a policy limit for this draft whole-read
path, not a claim about all OCI or a measured safe limit for every host.

A local Mac native fixture extracted the actual `ol-blob-code` and
`ol-reason` functions. Its existence and read providers were stubs. It
accepted the maximum-size descriptor far enough to report the stubbed missing
file, refused one byte above the limit and the observed 8.6 GB size, and
preserved malformed-digest and negative-size refusals. It also accepted a
matching empty blob and rejected a mismatched declared size with stubbed digest
and read functions. A control without the
size guard failed the same gate. No large file, model or registry was read.
A bounded native compiler source check also accepted a local unity composition
of this complete layout source with the existing SHA-2, JSON reader and OCI
artifact source. It did not compile or execute the complete layout component;
the dependency closure and real provider remain unqualified.

This is not a bounded untrusted-file reader: the descriptor may lie, the
underlying file may change after the check, and the layout/index reads are
not bounded here. It is not a complete OCI installer, verifier or sandbox.
The path must remain unavailable for untrusted or large layouts until the
provider enforces bounds and verification and consumption share a qualified
stable snapshot. Use the fune streaming work for that integration.

| Artifact | SHA-256 |
| --- | --- |
| changed source | `4b190bee9d5e2947110dd3aa0a2219e4bef9245c9d0606891653a67db1c44410` |
| signed native fixture | `a1d69cca5ef09fdf942541e9e87dab369e6995b4dc20c06996d1d8a137815756` |
| signed guard-removed control | `965a5e7c4075c9fc1f47b866976067278a1ebd3e51b208cde77d1a617f30727f` |
| Mac bootstrap compiler | `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` |
