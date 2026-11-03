# Compare directory snapshot identity with a trusted FD identity

`di-bound-inventory?` accepts the separate ADI2 diagnostic frame from Amu
PR #1810 and compares its device and inode bytes with expected values supplied
by a host before passing the contained directory records to the existing
strict ADI1 parser. `model-verified-bound-inventory?` first obtains the asset
mask from the digest-verified archive and staged files, then performs this
comparison and inventory check. This is an additional decision helper; the
existing recovery and loader do not call it.

On a local ARM64 Mac, the actual new snapshot helper and a native
source-derived fixture accepted a matching directory, including a matching
optional asset. Unknown files, corrupt assets, wrong digest, bad magic, zero
inode and a frame paired with a different directory's expected identity were
refused. A mutant skipping the inode comparison accepted the wrong-identity
case. The previous ADI1 parser regression also passed. The expected identities
in this fixture were obtained with `fstat` on the opened snapshot FD.

The comparison is not a trust boundary by itself. A deliberate test supplied
both a good frame and its good expected identity while asking to verify a
different target containing an unknown file; that still passed. The privileged
host must obtain and retain the target FD, bind the helper's successful output
and expected identity to it, ensure all asset reads use that same object, and
exclude concurrent modification and inode reuse. Neither caller-supplied ID
nor an ADI2 frame authenticates that relationship. No such production host,
recovery integration, real model, power-loss test, resource governor or
deployment has been qualified.

- Directory parser source SHA-256 `cadbf5ed7d96c601a6ec43a4ad1e7344eab67c3118125e055446eb54d11ad3a7`; composition `2cf82f6134311e50c74a5ddc6004bc5cb38fbfafac93fdf91d348cd90d63c1bd`; asset verifier `5221d296cc4d0c6042da82293b806786e4a34c56c65f72662e9b48e495d8446b`.
- Fixture source `2bd7c76657a08eb0b714f662fe14d75b18a1ba4123e65cda47d9e75ae7d94973`, signed binary `90b37e4c0877e3601b9cc912da557cdb16e9ec329ce67d3f83ac749bc5d782e5`, signed mutant `b10c38ff9313ef79a2610e731ee455768ec8ae1b0cd8e9af88975b7efd185d1f`.
- Signed ADI2 helper `8f8e63db8e32c532f650bb9504ae4dad29c1e7bff79baf6824339dd1b51746aa`.
