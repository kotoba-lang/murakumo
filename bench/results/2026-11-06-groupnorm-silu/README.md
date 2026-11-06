# GroupNorm + SiLU, f32 (I1 slice 1, JUN-927)

Recorded 2026-11-06T15:24:34+0900 on 21mbp.local, Apple M1 Max, load average 20.41 21.91 20.64. Diagnostic only unless the host is quiet.

- amu: `f6c6ea215a8c51383c5ebac0b39879cef780eafb`, `bin/amu` sha256 `ee9a94e406f90dd1c899121708bed01b44818f2c3bc0b9780f266b0408c61ff0`
- cc: Apple clang version 21.0.0 (clang-2100.1.1.101)
- command: `sh bench/kernels/groupnorm-silu/run.sh <amu checkout>` (C=320 HW=1024 G=32 REPS=20, shape of an SDXL ResBlock norm on a 32x32 latent)

```
reps=1 checksum equal: 174223520763243
reps=20 checksum equal: 93391298888609
mutant differs: 80224192282271 != 174223520763243
oracle: elements 327680 max_abs_err 5.027e-06 max_rel_err(|ref|>=0.1 floor) 2.206e-05
abs err <= 1e-4: ok
amu round 1: 35.03157 ms per pass (diagnostic)
c round 1: 2.093157 ms per pass (diagnostic)
amu round 2: 34.77578 ms per pass (diagnostic)
c round 2: 2.196315 ms per pass (diagnostic)
amu round 3: 35.33789 ms per pass (diagnostic)
c round 3: 2.137894 ms per pass (diagnostic)
```

## What this shows
- The Kotoba kernel's checksum equals the C twin's at 1 and 20 passes: every f32 step (sums, variance, rsqrt by `f32-div`/`f32-sqrt`, a polynomial exp, the final division) rounds identically.
- A mutant that moves one polynomial constant by 1 ulp changes the checksum, so the gate can fail.
- The f32 twin is within 5.0e-6 of an f64 reference (libm `exp`) over all 327,680 outputs.
- Kotoba is about 17x slower than `cc -O2` here (about 34 ms against about 2 ms per pass). That is the S1/S2 gap in the inference roadmap (vector access, per-element range checks), not a result for this kernel alone.

## What it does not show
- One shape, synthetic data, one thread, CPU only; no GPU, no checkpoint, no UNet.
- The oracle is the C twin's own math in double, not ComfyUI/PyTorch output.
- Timing is diagnostic: the host was not confirmed quiet.
