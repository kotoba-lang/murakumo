# Synthetic Flint OCI layout through native staging

On 2026-11-05 JST, a signed Darwin ARM64 selected-source diagnostic exercised
`model-flint-oci-stage-main` from an independently constructed private OCI
layout. The layout used small synthetic base and experts blobs (140 and 242
bytes), a synthetic seven-entry metadata tar and a matching Kapitsa file-list
manifest. It did not contain real model data. The diagnostic FS2 provider
was scoped to a task-owned temporary directory.

The valid case returned `flint-artifact/staged-file-list-verified`. Independent
reads matched all nine staged files, with mode `0600`; the stage and its
`.kapitsa` directory had mode `0700`. The following controls were observed:

* A wrong base digest and a stage nested in the source layout returned
  `flint-artifact/input-refused` without creating those stage paths.
* An incorrect manifest total and same-size corruption of either synthetic
  weight returned `flint-artifact/staging-unknown`. The invalid-manifest case
  left a partial stage, demonstrating why that result cannot be consumed.

The diagnostic deleted its task-owned temporary directory after the tests.
`scripts/verify-flint-full-stage-native.py` checks 16 selected function bodies
against the current tree before compiling the archived fixture; its scanner
comes from `scripts/verify-flint-oci-descriptor-native.py`. Other assembled
dependencies are historical and not source-locked. Explicit SHA-256 pins
are required for the diagnostic compiler and packager. Compiler and fixture
processes had a three-second CPU cap, without a hard memory cap. The script
signs and verifies its temporary image. `operator.py` is the exact earlier
task-specific runner and contains absolute local paths.

| Artifact | SHA-256 |
| --- | --- |
| Fixture source | `c24001e9d578ba7aa3296223838ce0d0c39f00fd8249d2f0352a3225e4cf9149` |
| Signed fixture image | `f8c463bbc2227c4f3adfd8b108b467c75c2e494ebfb82b93b29a593f27855370` |
| Reusable diagnostic | `041ce51080f315c649448ecab5776f11dc34b57ad337ad58eb7f9a92d15cfde1` |
| Original operator | `9919f1eb06290a5319c62afe022442686569fa30c38054d4da46ffd2942e4b94` |
| Current stage source | `ae58cffcaacdad7de84284f99084afc8478e04f908423f9063693578c1d88b67` |
| Current seal source | `7c716b316ab9c77a796bfb7417ec974a3b693a2093aed292441bb3c83a0485c8` |
| Compiler | `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` |
| Diagnostic packager | `df0e94211a35d84901ebcf2a8620cb653140d957732474ff77bd0cdd5b5bf4c5` |

This is not registry publication, production FS2, a complete immutable
snapshot, crash/power-loss recovery, real model staging or inference, a
source-locked selfhost product, or qualified resource governance. The host
must still prevent path aliases and concurrent mutations, and reconcile
partial stages before any use.
