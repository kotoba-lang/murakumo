# Cron request pending-state observation

The bridge now refuses both installation and head readiness if the request's
`.tmp` file exists or its FS2 existence result is anything other than `0`.
Previously, an already matching request bypassed the temporary-file check in
`br-install`. An already consumed request also bypasses `br-install` in the
controller, so `br-head-problem` checks the same condition before completion
can advance the cursor. This is a fail-closed check; it does not automatically
resolve or delete temporary files.

On one local ARM64 Mac using the diagnostic FS2 runtime, test fixtures copied
the actual two bridge functions and used real FS2 D/A for isolated paths.
The fixture substituted plain strings for CIDs and stubbed save, signed-head
and archive verification. `observe.py` checked a matching request, a pending
temporary file, already-consumed cases, a different request, missing request
with pending temporary file, and an injected unavailable FS2 result. It also
confirmed test files were unchanged. Old-order controls incorrectly reported
ready or already-consumed with a pending or unknown temporary file.
`observe-head.py` checked head-ready only with no pending temporary file and
refused pending/unavailable; the old-head control incorrectly reported ready.
The generators and observers use task-specific temporary paths. The fixture
does not test real CIDs, cursor persistence, archive, quorum, power loss,
concurrent mutation, a complete bot turn or a release Mac runtime.

Bridge SHA-256 `1b44c97d8d92848f08b239cd946e4e48d477d2d597c77e661b25fc7cad0a5555`.
Signed fixture, old-order, head, and old-head image hashes respectively:
`7a152fdf349bf7d7b5d7d197028b136e0d34900609cfc600fa30dc601d03c35a`,
`f61c6953cb5c4d1e63bf056b835a85f1cf383ab1f3de6ac7b149a348e14d1b18`,
`eb477475ac412f2440b81878af622ed58f03bdde799ec4dbdbca0c9c4db6cb34`,
`42bd70d817f51a988fcdb69d3dc42bfd1f41a77dc8656e285216ddebff1f7f47`.
Bootstrap compiler SHA-256
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`;
diagnostic packager SHA-256
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
