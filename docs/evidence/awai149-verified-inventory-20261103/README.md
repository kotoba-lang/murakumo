# Derive the inventory mask from verified model assets

The directory parser previously accepted an asset mask supplied by its caller.
`tv-walk-mask` now returns the exact asset-name mask only after it has checked
the complete pinned USTAR digest, required names, staged file bytes, modes and
known absent optional files; it returns -1 on refusal. The existing `tv-walk`
boolean wrapper preserves the recovery caller's behavior. The new
`model-verified-inventory?` composes this derived mask with `di-inventory?`;
it is a decision helper and is not yet wired into recovery or a loader.

On one ARM64 Mac, a native fixture used the actual functions and output of the
Darwin directory snapshot helper on small synthetic files. The base archive
returned mask 255 and matched its snapshot. An archive and target with one
valid optional asset returned mask 511 and matched. Wrong archive digest,
changed or missing staged files, a known optional file absent from the archive,
and an unknown directory entry were refused by the relevant composed tests.
The existing conditional model recovery fixture was rebuilt and repeated: ten
corruption/unknown cases refused, and both intended lost-reply cases recovered.

**The host boundary remains essential:** supplying the good directory's frame
while checking the directory containing an unknown file still passed. The
frame was caller-provided, so the composition cannot establish that it came
from the same open directory. A privileged host must bind helper completion,
output and directory FD to the exact exclusively controlled target for the
entire validation; no current production host or loader does so. In
particular, the original recovery entry still does not call this helper and
can accept an unlisted file under its documented conditional assumptions.
No real model, registry, inference, power-loss test, independent host,
resource governor or deployment was exercised.

- Asset source SHA-256 `5221d296cc4d0c6042da82293b806786e4a34c56c65f72662e9b48e495d8446b`; directory parser `635350ef2e3a44ab69c182fefd8e01ec67285c6c073e6dd5c50a6eb8bf3772f2`; composition `56b244f3b088739a5b753aaedd75327ac1e463253c517a77189f3eda32d981dd`.
- Native fixture source `e2d09d4670a504c167d9f8b7f72c8be7925445aa47057339bbef074c2eafe6d9`, signed binary `10e6eecf02426842c64e745381559bf3a00e5bda77e7ed58fb8d16843d389924`; snapshot helper `6a3bd573d8c157792cc7d782f1b1343bd8af553bd566d5e6f9bde2a5709f7223`.
- Rebuilt conditional recovery fixture `56a37e772ce353ce43cd2d508e0fe9169037fd7fd5fdd83bc288e3f0e78a49f6`.
