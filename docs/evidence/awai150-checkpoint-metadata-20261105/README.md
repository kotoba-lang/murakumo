# Checkpoint metadata refusal

`cp-size` previously extracted only size and file kind from an FS2 `O`
response. A malformed mode, block count or trailing field could therefore be
accepted as a regular file. It now requires exactly four fields, decimal
values within the existing integer parser's bounds, mode 0..511, nonnegative
size and block count, and regular-file kind 0. The host must still own paths
and prevent replacement or same-size writes across separate operations.

The selected current `cp-size`, `cp-int` and metadata field parser ran in a
Darwin ARM64 native fixture with injected responses. Nonempty and zero-length
regular-file metadata passed. Invalid or out-of-range mode, invalid blocks,
extra/missing fields, nondecimal size and directory kind were refused. The
old parser mutant failed the invalid-mode, mode-range, invalid-block and
extra-field tests. Source SHA-256:
`e5116bd8b7597170e78659a3156454febe1b21e81e145229fd8b7c6315f298af`.
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
