# The linear layers: what did not help, and what did (stage M slice 24, JUN-927)

Recorded 2026-11-06T19:57:44+0900 on 21mbp.local, M1 Max, load average 5.15 5.92 8.22 (7 to 9 for these runs, against 9 to 50 earlier). amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`. Timer: the per-kernel GPU time of slice 23 (`bench/kernels/unet-sdxl-profile`), 512 px, the 722 `linear_t` dispatches of one call, three alternated runs per variant, two passes each.

## Variants of the linear kernel (GPU time of all linear_t dispatches, pass 1 / pass 2, ms)
| variant | pass 1 | pass 2 |
|---|---|---|
| baseline: weight [N, K], transposed 8x8 loads, k step 8 | 306 to 348 | 359 to 453 |
| threadgroup staging, K tile 32 (slice 13's idea) | 358 to 383 | 463 to 490 |
| threadgroup staging, K tile 64 | 363 to 373 | 445 to 485 |
| k unrolled by 2 | 540 to 551 | 677 to 682 |
| k unrolled by 4 | 481 to 497 | 638 to 692 |
| 8 simdgroups of 32 x 16 instead of 4 of 32 x 32 | 422 to 451 | 483 to 567 |
| **weight stored [K, N], plain (untransposed) 8x8 loads** | **255 to 267** | **329 to 334** |

- Slice 13 reported staging as "no measurable gain" under a noisy wall-clock; with the GPU timer it is measurably **slower** (15 to 20 percent), as are unrolling (register pressure: 1.6 to 1.8 times slower) and eight simdgroups. The kernel's structure is a local optimum; what costs is the **transposed load** of the weight tile.
- The change: the weight is converted from the file's [N, K] float32 to a half [K, N] buffer by a transposing conversion kernel (`cvt32h_t`, same one-dispatch-per-weight as before, wrapper `cvl!`), and `linear_t` loads it untransposed. About 16 percent less linear time per call (258 against 307 ms pass 1 at 512 px on the lighter-host comparison above; 327 against 375 ms pass 2).

## Correctness (unchanged numerics: the math and accumulation order are the same, errors are identical to the digit)
All against ComfyUI's own classes: the SDXL-topology UNet at 256 and 512 px (21 stages, worst 8.3e-3 / 9.0e-3, final 1.2e-3), now with a sixth mutant that makes the transposing conversion a plain copy (final error 0.48, detected); the VAE decoder (6.2e-3 at 512 px, 6.3e-3 at 1024 px); the mini UNet (1.37e-3); and the whole text-to-image render (ids exact, conditioning 1.0e-2, steps 3.6e-2, image 1.7e-2, seven mutants detected). Applied to the five render-path programs (mini UNet, the timing/profile programs, the named UNet, the pipeline including both text towers' linear layers and the VAE attention); the standalone CLIP tower, text-encode, spatial and GEMM gates keep their own [N, K] kernel.

## Wall clock per UNet call (slice-11 timing program, synthetic weights, load average 7)
**0.56 s at 512 px, 1.12 s at 768 px, 2.32 s at 1024 px** (slice 23: 0.62 / 1.52 / 2.87; slice 11: 0.84 / 2.27 / 4.49, under much heavier load). At 768 px the 50 calls of a 25-step guided render are about **56 s** of UNet time on this M1 Max, against the 72 s ComfyUI took on a 16 GiB M4 mini that the I3 ADR used as its reference: faster than that reference, on a different and smaller GPU, for the UNet only, with synthetic weights and a lighter host than before (comparisons across hosts and loads are not clean).

## What it does not show
- The text encoders and the VAE decode are not in those 56 s (CLIP-L + bigG: about 80 ms per prompt; the VAE decode at 1024 px: about 6 s).
- Synthetic weights in the timing program; no trained model was run, and ComfyUI was not run on this Mac for a same-chip comparison.
- The linear layers are still the largest block (about 258 ms of ~600 per call at 512 px) at roughly 2.1 TMAC/s against an f16 peak near 5; the 3x3 convolution is next at about 1 TMAC/s.
