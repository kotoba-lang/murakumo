# Model digest metadata preflight

The Darwin diagnostic FS2 F handler checks for a regular file and deliberately
traps on a directory. The digest candidate now asks for no-follow O metadata
first, and requires a regular file and the expected size before asking for F.
On one local Darwin ARM64 host, both the earlier and the extended-range
diagnostic providers refused directory and final-component symlink inputs
with `model/size-unknown`; an ordinary 65,537-byte file still matched the
independent SHA-256. The earlier provider was also rerun across all SHA-256
padding and 64 KiB range-boundary cases in the preceding evidence.

The diagnostic E provider's permitted starting-offset profile is below 16 GiB,
not the original candidate's 64 GiB file bound. This candidate now rejects a
requested size above 16 GiB; a 16 GiB request with mismatched metadata also
refuses. The extended-range diagnostic provider is needed for offsets above
4 GiB. A sparse 8,595,477,990-byte file occupied zero allocated bytes; with
that provider the candidate accepted its metadata and a deliberately shortened
first E response produced `model/read-unknown`. It was removed afterward.
The actual 8.6 GB model was not read, hashed, copied or loaded.

O and F are separate pathname operations: a concurrent replacement after O
can still trap or change the bytes. The host must enforce exclusive scope and
the eventual verifier/consumer needs a stable snapshot. The F provider itself,
provider errors, large-file throughput and resource control remain unqualified.
This candidate is not an OCI manifest parser, model identity grant or deploy.

| Artifact | SHA-256 |
| --- | --- |
| observer source | `0a639cbf58417643a0f1f4b7d3c23f9c7ff04af2aa37b32e9a90bfa82fbcaba1` |
| fixture | `8fe6d44c41b273d239e14d1d9f7588392e15c56e164306d8938d9a43c74ee75d` |
| short-read fixture | `5f87a1ae2fbfb9c792f9793b9e90ddd916108181f03e5c1514b4602e4d318452` |
| signed earlier-provider fixture | `30c497178f1a7f15ea817390066e4e7060cd94b55cb4ad42404ffd8e8864bfa3` |
| signed extended-provider short-read fixture | `a834b032146923e66975b53768a0070975f79d898c7fd85d788769272044b700` |
