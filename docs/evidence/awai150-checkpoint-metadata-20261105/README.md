# Checkpoint metadata refusal

`cp-size` previously extracted only size and file kind from an FS2 `O`
response. A malformed mode, block count or trailing field could therefore be
accepted as a regular file. The dependent base independently added
`cp-meta-size`; the conflict resolution uses that implementation: exactly
four fields, decimal mode 0..511, size of at most nine digits, block count of
at most 20 digits, and regular-file kind 0. The host must still own paths and
prevent replacement or same-size writes across separate operations.

The selected current `cp-size`, `cp-int` and metadata field parser ran in a
Darwin ARM64 native fixture with injected responses. Nonempty and zero-length
regular-file metadata passed. Invalid or out-of-range mode, invalid blocks,
extra/missing fields, nondecimal size and directory kind were refused. The
old parser mutant failed the invalid-mode, mode-range, invalid-block and
extra-field tests. A 20-digit block count passed and 21 digits were refused.
Merged fixture source SHA-256:
`946f58feee418b801d3118cceb828c070c3aced72c686f0cd72978f04b0e394a`.
Compiler SHA-256:
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`;
diagnostic packager SHA-256:
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.

The existing real-file `provider.py` was refreshed to use the actual updated
`cp-size`: readable zero- and one-byte blocks passed, and unreadable zero-byte
input trapped in the diagnostic provider. These samples do not qualify a
production provider, filesystem races, GitHub, a full checkpoint or governor.
The test scripts depend on local pinned artifacts and are not deployment
commands.

After merging the dependent base branch, the bounded-write fixture generator
was updated to include `cp-meta-digits?` and `cp-meta-size`. The current
bounded-write native fixture passed with independent readback, and its
comparison-bypass mutant failed. Component SHA-256:
`cd56fc6e5aef24dabcce12d6b8b3c09ec1de3e389e33bc8632d05b06ad6ede9d`;
fixture SHA-256:
`00b2e7df41b5e75c0aa16650bae65786a93713eeb56c8f17763e55a69389066a`.
The test used the same pinned diagnostic compiler and FS2 packager above,
with a three-second CPU limit, and is not a full checkpoint or power-loss test.

The merged source also passed the existing task-owned local-Git fixture with
`--local-archive`: capture, verification, restore, successor and recovery
record, local bare-Git archive and restore passed; wrong policy, existing
destination, corrupt block and wrong recovery epoch were refused. The fixture
assembled all 69 current checkpoint component functions with a privileged
test-only Git spawn adapter. The checkpoint component SHA-256 was
`cd56fc6e5aef24dabcce12d6b8b3c09ec1de3e389e33bc8632d05b06ad6ede9d`;
the generated checkpoint and archive fixture SHA-256 values were
`6499933c27a4fb1be95fc6b6adffb4e3acce642f5a9ff82f8046f27ae4d0bcdf`
and `1edd4dce0f9fd93ae1ce0f72f9488c8d2ad04224ee28b5c97765d405bf9fbe08`.
It used compiler pin `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`
and test-only Git packager pin `cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`,
under a three-second CPU limit. This is local Git, not GitHub, a production
provider, workload or governor; its temporary directory was removed.
