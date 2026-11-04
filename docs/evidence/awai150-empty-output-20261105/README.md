# Existing empty output block readback

`jo-put` may reuse an existing content-addressed block. The prior existing-
block path compared size, returned bytes and CID, but an empty vector can
mean either a valid empty FS2 response or an unreadable/malformed response.
For size zero it now additionally requires the exact empty FS2 frame via
`cp-empty-readable?` before file or directory synchronization is treated as
success. Nonempty blocks retain the existing bounded-read checks. Provider
traps and unstable files remain unknown; the trusted host must still prevent
replacement and same-size mutation.

The selected current `jo-put` ran in a signed local Darwin ARM64 native
fixture with injected metadata, read and sync functions. A readable empty
block and a nonempty block passed; an unreadable empty block returned unknown
without syncing. A mutant that removes the new guard failed the unreadable
case. Generated source SHA-256 was
`0fffd90008a541412946bbbc0eb40805b89ad49dfc4abe72dc0ccacb4deb89a6`.
Compiler SHA-256 was
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`;
diagnostic packager SHA-256 was
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.

This isolates the actual decision with injected FS results. It is not a real
kernel read-error, full checkpoint/GitHub roundtrip, production provider,
workload or governor qualification. `diagnostic.py` uses local historical
paths and is a test instrument rather than an operator command.

`provider.py` additionally runs the current `jo-put` and empty-frame check
with the pinned Darwin FS2 diagnostic provider and task-owned real files.
The retained historical unity fixture's frame, read and CID helper forms are
checked byte-for-byte against current source; the current `cp-size` is
substituted explicitly. Readable empty and
one-byte existing blocks succeeded. An empty mode-000 file caused the
provider to terminate the diagnostic with SIGTRAP during the read; it did not
return a controlled refusal. The host must preserve this interruption as an
unknown outcome. This real permission failure does not distinguish the new
guard from the prior implementation, because the provider traps earlier;
the injected failure test above covers that branch. Generated source SHA-256:
`e444758bd6e2387ce254f8fab31893afb8ff164b81f4bde05837b7a433f878cf`
after the metadata validation update.
The task-owned temporary directory was removed. This does not qualify the
production provider, actual GitHub archive, or governor.
