# Bounded OCI layout reads: local diagnostic

The draft layout verifier refuses descriptors larger than 4 MiB. FS2 F obtains
the actual file size before allocation; FS2 E reads at most 256 KiB per call,
and the guest checks the response framing and exact length. The OCI marker and
index have separate 1 KiB and 1 MiB bounds. FS2 F/E support is now required;
this is a compatibility change. Large model weights require a separate artifact
path, rather than this image-layer verifier.

On a Mac, a native diagnostic executable composed the actual changed layout
source with the existing SHA-2, JSON, and OCI sources and the draft Darwin FS2
provider. It returned success for a small valid OCI layout, digest mismatch for
a corrupted layer, size mismatch for a sparse 8,595,477,990-byte layer whose
descriptor claimed nine bytes, and index mismatch for a sparse index of that
size. A separate native fixture checked an empty file, a 262,145-byte file
across the chunk boundary, rejection above a four-byte limit, and a sparse
8,595,477,990-byte file. The sparse files occupied no model-sized disk space.
A source check accepted the full composition. These are local diagnostics, not
validation of a production provider, registry, or fleet deployment.

The bounded reader is not a snapshot: metadata, ranges, repeated manifest reads
and later import may see different file versions. Same-size concurrent changes
and provider behavior remain unqualified. Do not treat this as a verifier for
untrusted layouts until a qualified stable snapshot is shared with consumption.

The manifest is now hashed again when its bytes are read for parsing and when
its config digest is read separately for launch. In a second full-source Mac
fixture, the valid layout and config passed, an invalid manifest digest was
rejected, and a same-length valid JSON manifest with a changed config digest
was refused after the earlier valid check. This narrows a read-to-read gap; it
does not create an atomic snapshot for the entire layout or the later import.

| Diagnostic artifact | SHA-256 |
| --- | --- |
| current layout source | `d7db899f0d0aa903caef1b2aca82b6e875ea2fa75974ce70b150ff188dc47a74` |
| signed manifest authentication fixture | `513ef3e8f1140aa36ef8672f8c1231c70da97617ec1e01e119de1254b18341b5` |
| signed original complete-layout fixture | `f015612be5c4d10338335283553bdf90c1d48823b45372bbfd799ea4f0be16be` |
| signed bounded-reader fixture | `842b1270bb0d9a7d1813aba5cf12c05fa03428f50270b90ea2a009eab163d44e` |
