# Cron request pending-state refusal

A request `.tmp` file can remain after an interrupted replacement. Previously
`br-install` returned ready when the visible request already matched or returned
already-consumed when the head matched before checking that temporary file.
The completion check did not consult it. The bridge now reports
`bridge/request-unknown` before either preparing or accepting completion when
the temporary path is present. The controller must reconcile the write before
it can advance. A private, exclusive host namespace remains a prerequisite.

On a local ARM64 Mac, a small source-unity fixture compiled the actual changed
`br-install` and `br-head-problem` functions with stubbed filesystem, JSON,
and admission functions. It checked pending temp with visible matching and
consumed heads, and clean ready/completed cases. The fixture exited 0; a mutant
removing both pending checks exited 1. It did not exercise the real filesystem,
interrupted rename/fsync, controller, GitHub, inference or concurrent writers.
Existing `cp-exists?` does not distinguish an FS error from absence, so the
trusted host must provide the expected filesystem and protect the namespace;
this change does not qualify unknown-error handling or host serialization.

Bridge source SHA-256 `fd25807c04b468a10461cf0ca48f55d671935c1135eccfd02f1e5d487ff69cac`;
fixture source `79e7e4cff6f174314a892c202f5e25db290476c60bc31896f49bf2c60ee29fd9`;
signed fixture `814cf0dce7eebf35d01c1f9b1d0cbf611834007ec82256fdae98b7f57ca0fe8d`;
signed mutant `a15ff4b9d65bbaaf86e5a8db86fad265cfb27dbb147214ecf4be4bf04f108cc8`.
