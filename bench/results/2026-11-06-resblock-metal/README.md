# An SDXL-style ResBlock end to end on the Apple GPU (stage M slice 8, JUN-927)

Recorded 2026-11-06T17:09:14+0900 on 21mbp.local, Apple M1 Max, load average 11.24 13.50 12.53 (busy host: timing is diagnostic).

- amu: `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, `bin/amu` sha256 `3839d2616ba737f59835cb17bb58d79543c99098da73ffe17a67a7039427dd7c`, profile `amu-program-metal` (ADR 0405)
- cc: Apple clang version 21.0.0 (clang-2100.1.1.101)
- command: `sh bench/kernels/resblock-metal/run.sh <amu checkout>`

```
ResBlock 32->64 16x16 GroupNorm 32: gpu_err=4.931e-03 (limit 0.02) cpu_f32_err=9.447e-06 ok best=0.81 ms for 6 dispatches
mutant [skip]: error 6.354e+00 (detected)
mutant [tembias]: error 5.618e+00 (detected)
mutant [gnaffine]: error 1.011e+01 (detected)
gate passed
```

## What this shows
- A whole ResBlock (GroupNorm+SiLU, 3 x 3 convolution with a time-embedding bias, GroupNorm+SiLU, a 1 x 1 skip convolution, a 3 x 3 convolution with bias and the skip as a residual epilogue) runs on the GPU from one Kotoba program built by `bin/amu`: six kernels encoded into **one command buffer and one encoder**, each reading what the previous wrote (a half activation feeds each convolution; the time-embedding kernel folds `c1 + bt + linear(silu(temb))` into the first convolution's bias; the skip convolution's output is the second convolution's residual). 32 -> 64 channels on 16 x 16, 32 GroupNorm groups (ComfyUI's), time embedding 64.
- It uses the same parameters as the CPU ResBlock of I2 (`bench/kernels/resblock`: the same LCG stream, order and scales), converted on the GPU: weights and the skip input to half, the rest to float. Against the double-precision ResBlock built from `resblock/impl.h` the GPU is within **4.9e-3** (|gpu - ref| / max(1, |ref|), outputs up to 14.3); the f32 CPU twin is within 9.4e-6 of the same reference. The difference is the half-precision convolution inputs and weights, the scale the f16 GEMM slice measured (about 1e-2 for K of a few hundred), not a kernel disagreement.
- Three mutants are detected (error > 0.1): the skip dropped from the final residual (6.4), the time-embedding bias dropped (5.6), GroupNorm's affine taken per group instead of per channel (10.1).
- The six dispatches take about 1.1 ms in all (best of 20, host load average 17 to 20, M1 Max).

## What it does not show
- One small block, one shape, synthetic weights drawn uniformly, not a trained layer. The limit (2e-2) is the f16 GEMM scale rounded up; I had seen the 4.9e-3 reading in a first run before writing the script, so the limit is not an independent prediction.
- The kernels are the earlier slices' (the convolution reads its parameters from a small buffer so one kernel serves all three convolutions); no fusion between them, no tuning, GroupNorm uses one threadgroup per group so 32 threadgroups of 128 threads. 1.1 ms for about 0.14 GMAC says this size is nowhere near the matrix units' rate.
- Not compared with ComfyUI's ResBlock on the GPU path (the I2 comparison was twin versus ComfyUI in float32 on CPU, 7.6e-6). Not a SpatialTransformer. M1 Max only; the dump goes to stderr and is split by byte count. Not a CI step (needs a GPU host).
