# Expected OCI identity from observed public assets

This is an observation, not a published artifact, trusted-publisher attestation,
model/runtime qualification or execution authorization. Source observation and
its discrepancy are in [PR #587](https://github.com/kotoba-lang/murakumo/pull/587).

At the immutable third-party Hugging Face revision in `observation.json`, all 15
asset files were downloaded and independently checked against the sizes and
SHA-256 values in the pinned source observation. In two separate reads, test-only
Python code streamed USTAR bytes into SHA-256 without writing an archive: one
constructed the header fields directly, the other used Python `tarfile` USTAR
headers. Both used the recorded fixed file order, mode 0600, zero uid/gid/mtime,
512-byte padding, and exactly two terminating 512-byte zero blocks. Both
obtained the recorded tar size and digest. The compact sorted manifest bytes
were independently computed from that digest and the two weight descriptors
in the source observation. The 8.6 GB base weights were not downloaded in this
experiment. The current public README matches the revision bytes but differs
from its own published pack manifest; this observation does not resolve that
policy discrepancy.

The native candidate and its tests have not executed. The tar digest is a
conditional expected value: it is useful only if the private host independently
accepts the exact pinned observation, exclusion of extra source entries, and
header policy, and verifies actual inputs and output. No OCI tar, layout, registry
push, model loader, resource governor or live bot was exercised here.
