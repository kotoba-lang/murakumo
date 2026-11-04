# Synthetic Flint stage and inventory verification

On 2026-11-05 JST, a signed Darwin ARM64 selected-source fixture first ran
the [synthetic full-stage case](../awai149-flint-full-stage-20261105/README.md)
and then called `model-flint-parent-verified-inventory?` against the staged
files and pinned synthetic layout. A Python test operator synthesized ADI2
directory frames and an ADP1 parent frame from path-based `stat` and directory
enumeration. These frames were not produced by the native helpers and are not
authenticated or bound to held file descriptors.

The complete synthetic stage returned `inventory/verified`. Same-size changes
to a metadata file or staged weight, an extra entry, and a malformed parent
frame each returned `inventory/refused`. After restoring the files and
removing the extra entry, the stage verified again. A stage whose metadata
manifest had an incorrect total returned `flint-artifact/staging-unknown`;
the corresponding synthetic inventory verification also refused. The earlier
wrong-pin, overlapping path and corrupted-source-weight controls remained
refused. The private temporary directory and its partial stages were removed
after the diagnostic.

`scripts/verify-flint-stage-inventory-native.py` checks 37 selected function
bodies against the current checkout, builds the archived fixture with pinned
diagnostic tools, signs and verifies the image, and reruns these cases. The
remaining assembled dependencies are historical. Compiler and fixture
processes had a three-second CPU cap, with no hard memory cap. The archived
`operator.py` is the task-specific original with absolute paths and is not an
unattended command.

| Artifact | SHA-256 |
| --- | --- |
| Fixture source | `405ef5f0f6f3230b9e381bece7ac10d4493439453be1a361a56093a5c4eddc41` |
| Signed fixture image | `d86a9c3db6f251e7f7c987c162279b2150a7be33d284b2c4eec54bd8e61860f9` |
| Reusable diagnostic | `c185373e1648b9d089339911593e62a06827b0023da8f999341adab23cffbdeb` |
| Original operator | `2153e2cc033f2ed779391ce9902563bd2e0eecea12ba18a3a6c832149dd69c86` |
| Flint verified inventory source | `b876cf6e649ea0405d24d357d8c3522e5f252304c55a34e89eca67f4ed2cce15` |
| Flint directory inventory source | `241fc1f24d0314ba27f06ac79f9334d9d1a58ac0084ed66308eb8e47ba638b9c` |
| Directory inventory source | `cadbf5ed7d96c601a6ec43a4ad1e7344eab67c3118125e055446eb54d11ad3a7` |
| Compiler | `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` |
| Diagnostic packager | `df0e94211a35d84901ebcf2a8620cb653140d957732474ff77bd0cdd5b5bf4c5` |

The result does not authenticate the synthetic inventory or prove the stage
is immutable. A qualified host must hold and bind the actual root and child
FDs, verify their parent-child relationship, exclude aliases and mutation,
and reconcile interrupted publication. No real model, registry, production
FS2, selfhost product, inference or resource governor was tested.
