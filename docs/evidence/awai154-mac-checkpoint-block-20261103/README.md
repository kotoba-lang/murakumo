# Mac checkpoint block helper diagnostic

The [fixture](fixture.kotoba) uses the actual `cp-bytes`, `cp-frame`, `cp-fs`,
`cp-exists?`, `cp-read-op`, `cp-read`, `cp-cid`, `cp-cut`, `cp-piece`,
`cp-write` and `cp-put` functions extracted unchanged from the checkpoint
source pinned in the [receipt](receipt.json). SHA/CID helpers are composed
into the fixture; the host independently computed the expected SHA-256 CID.
The Amu diagnostic Mac compiler and packager emitted a Mach-O containing the
candidate Darwin FS2 runtime from Amu #1796.

On one Mac using task-owned local files, a synthetic 8,197-byte block with NUL,
255, and bytes crossing the checkpoint chunk boundaries was created and read
back. Reusing the same CID was idempotent. A modified existing block was
refused without replacement. Replacing the object with a symlink caused a
runtime trap and did not modify its target. The fixture also exercised a
four-byte range read through the checkpoint's string-to-bytes capability.

The test is a helper-level diagnostic, not a complete checkpoint component
qualification. It does not invoke Git, GitHub, independent hosts, model
inference, bot execution or a resource governor. It does not establish
power-loss durability, concurrent mutation safety or a production selfhost
release. A trap is reported as an uncertain failure, not as a normal typed
refusal.
