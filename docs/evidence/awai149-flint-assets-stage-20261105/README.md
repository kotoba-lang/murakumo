# Native synthetic Flint metadata staging

On 2026-11-05 JST, a signed Darwin ARM64 selected-source fixture ran the
current `ft-stage`, `ft-walk`, `ft-id` and `ft-name` against an independently
created 10,240-byte USTAR containing the seven expected metadata paths,
including `.kapitsa/manifest.json`. The archive contained small synthetic
strings, not model metadata or weights. The fixture used a diagnostic
extended-range FS2 provider scoped to its task-owned temporary directory.

The valid case returned `flint-assets/staged`. Independent file reads
matched all seven source byte strings and found mode `0600`. An existing
stage returned `flint-assets/input-refused`. An incorrect digest and archives
with a missing entry, duplicate entry, traversal name or symlink returned
`flint-assets/staging-unknown`; these are not claims that no partial files
were written. The portable diagnostic also checked that the traversal case
did not create its escape target in the scoped root. Temporary stages were
removed with the task directory after the tests.

`scripts/verify-flint-assets-stage-native.py` verifies the four selected
function bodies against this checkout before building; it imports the form
scanner from `scripts/verify-flint-oci-descriptor-native.py`. Other fixture
dependencies are historical and not source-locked. Compiler and fixture
processes had a three-second CPU limit; no hard memory limit was applied.
The script requires explicit pins for the compiler and diagnostic packager,
signs and verifies its temporary image, and deletes its temporary directory.
The original task-specific operator is archived separately as `operator.py`;
it uses absolute local paths and is not an unattended deployment command.

| Artifact | SHA-256 |
| --- | --- |
| Synthetic tar | `50f4df8475a97f815ae9f696fa9034e8cabd13e50ae71a0390be403c6e269eb6` |
| Fixture source | `d2bf4eb1fb90031c57502a7019d7c4e2e9148104d1bf2ac5b533537ee785721a` |
| Signed fixture image | `2fdfa7cc41b0e9ce280fee73b486e1b55fbd9b217e328d8119e73601afc7cf0c` |
| Portable diagnostic | `f58d06c9c2310ec80e52763e564c04d245c50205d356fb5f0be975787f5a8e6a` |
| Archived original operator | `ebacc268095ab0658c48a6d8d4dba80faf4535d15085a92ff6a62fb23dfd353f` |
| Current stage source | `ae58cffcaacdad7de84284f99084afc8478e04f908423f9063693578c1d88b67` |
| Compiler | `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` |
| Diagnostic packager | `df0e94211a35d84901ebcf2a8620cb653140d957732474ff77bd0cdd5b5bf4c5` |

This is metadata tar staging only. It does not execute the full Flint OCI
layout validation, weight copying, manifest semantics, publication, real
model, production FS2, source-locked selfhost build or qualified governor.
It does not prove all archive failure modes or crash/power-loss recovery.
