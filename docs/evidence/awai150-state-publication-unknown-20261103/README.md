# Interrupted profile state publication

`pr-step-range` now requires explicit absence of `state.json.tmp` before it
enters inference dispatch. An existing file or an unknown lookup stops the
step with `profile/state-publication-unknown`; the host must reconcile the
private task directory under its existing serialization boundary. A provider
that returns `0` for lookup errors cannot establish absence.

On 2026-11-03 an unprivileged user ran the two attached fixture images in a
1 CPU / 2 GiB Linux ARM64 VM. They embed the current and immediately preceding
`pr-step-range` respectively, with test stubs for identity, model, unit
management, checkpoint publication and most filesystem calls. FS2 D and B
for the test path actually used the Linux runtime; the stubbed unit lookup
printed `ENTERED-DISPATCH` if execution reached the dispatch path. No model
or real system service was called.

| Setup | Candidate | Old control |
| --- | --- | --- |
| Existing temp file | refused before dispatch; original bytes preserved | entered dispatch and overwrote the temp file |
| Temp file mode 000 | refused before dispatch; original bytes preserved | not tested |
| Temp file absent | entered the stubbed dispatch and finished | not tested in this fixture |

The mode-000 permissions were restored. The one-off fixture does not prove
power-loss durability, race freedom, a real inference call, GitHub storage,
distributed locking or an autonomous bot. The test compiler was a byte-patched
diagnostic copy with the FS2 D candidate from Amu #1796, not a qualified
compiler. The root directory and single-writer guarantee remain host duties.

Source SHA-256: `bcb2583ed1aa830eacc6d17fcc0a9e6cfd026aaba315409685b40a9da9717d99`.
The diagnostic compiler SHA-256 is
`4a42e07083beb87337ad56dbf9759890e48d4502154e361d54cfee0b71e89da9`.

| Fixture | Source SHA-256 | Executed ELF SHA-256 |
| --- | --- | --- |
| candidate | `0082db4a2782760179dca5ef18e8f8d4eea37a09dcdcebccf5eaa755de325e31` | `1eba68ba245f432e945b4efa324c17d1a4ffe71fd84c5e43c22c5ab9cf0bfa4c` |
| control | `4aadc4331180a810f697a4a0b6e4f87f0432fdb4414c552ae61f5055d1d3ae80` | `b45b401441158e00ac1cae224d0a91af61f951ee9650c9c3b79fb018d71ebce6` |
