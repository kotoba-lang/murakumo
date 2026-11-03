# Conditional model publication recovery

`model-artifact-recover-main` addresses only the case in which the stage name
is absent and the destination already exists. Under an exclusively owned
private scope, it validates the exact intent or completion marker and receipt
against host-pinned manifest and layer digests/sizes; rechecks the OCI
manifest, hashes both staged weights, and compares every asset in the pinned
USTAR against the corresponding staged file while hashing the tar. It checks
the expected modes, rejects unexpected *known optional* asset names, syncs the
validated files and directories, and either moves the intent to `.complete`
with no-replace rename or resyncs an already present matching completion marker.
It does not reconstruct or remove partial stages, dispatch work or retry a
model launch. Failure or a lost response remains unknown.

On one Darwin ARM64 Mac, a native fixture with diagnostic FS2 used synthetic
artifacts and the actual target/markers left by publication lost-reply tests.
It recovered both the intent-present and completion-present cases. Modified
base, MTP, asset, receipt or marker, wrong mode, symlink, a known optional file
not in the tar, an existing partial stage and both markers were refused. A
fault fixture discarded the reply after the real intent-to-complete rename;
the first call returned unknown and a later full revalidation recovered the
completion-present state. The publication regression also passed after the
shared intent/receipt construction was factored.

**Important limitation:** this FS2 profile cannot enumerate arbitrary
directory entries. A test adding an unlisted `unlisted.txt` to a target still
returned `model-artifact/recovered`. The result is therefore conditional on a
host-enforced private directory that excludes all other writers, aliases and
unknown entries from initial creation through recovery, including across a
crash. Mode checks do not qualify ownership, ACLs or this isolation. A loader
must not treat this result alone as authorization. A qualified directory
inventory or an independently enforced fixed-input loader is still needed.

No actual model, real registry, process restart, power loss, production
provider, governor or inference was tested. Rehashing real weights is high
load and must itself pass the qualified resource governor. Receipt and marker
are not signatures; publisher identity, atomic cross-host state, sealed output
and crash cleanup remain open.

| Artifact | SHA-256 |
| --- | --- |
| recovery source | `85323f565dd805b039ace18dc4bd7aef843ddefeded21052bcb537a7eb35189a` |
| native test fixture | `5fc55a72951501539aa349ee6e707db5888ec967fed64b2ad9cdeb304b3500f6` |
| lost-reply fixture | `5e20864bd3affaa6841ebb31d22a43e48ba49a34f8762b863fd24f6844bd1bc2` |
| signed test fixture | `cbcba4ebd8a3048eb98229ff8f7d9c09484bac06943e09bf7dbb6512bbb64337` |
| signed lost-reply fixture | `ad2cdb8c5e4aa846ce672d25bc94e1a84f562050d483bff993653fa63a8774d9` |
