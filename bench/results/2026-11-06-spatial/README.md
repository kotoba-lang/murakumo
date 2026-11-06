# SpatialTransformer block composition, f32 (I2 step 2, JUN-927)

Recorded 2026-11-06T15:57:08+0900 on 21mbp.local, Apple M1 Max, load average 26.04 26.82 25.78 (busy host: timings diagnostic).

- amu: `f6c6ea215a8c51383c5ebac0b39879cef780eafb`, `bin/amu` sha256 `ee9a94e406f90dd1c899121708bed01b44818f2c3bc0b9780f266b0408c61ff0`
- cc: Apple clang version 21.0.0 (clang-2100.1.1.101)

## C=32 8x8 tokens (64), 2 heads, 16 context tokens of dim 24, GEGLU inner 64
```
reps=1 checksum equal: 60222207841606
reps=3 checksum equal: 222884817857696
mutant differs: 129073644323111 != 60222207841606
oracle: elements 2048 max_abs_err 3.838e-06 max_abs_ref 1.020e+01
abs err <= 1e-4: ok
amu round 1: 14.38000 ms per pass (diagnostic)
c round 1: .7050000 ms per pass (diagnostic)
gate passed (SC=32 SH=8 SW=8 SG=8 SNH=2 SLC=16 SCC=24 SFI=64 REPS=3)
```

## C=48 6x10 tokens (60), 3 heads, 11 context tokens of dim 20, GEGLU inner 40 (gate only)
```
reps=1 checksum equal: 181838557113702
reps=2 checksum equal: 127362171450223
mutant differs: 185538854296514 != 181838557113702
oracle: elements 2880 max_abs_err 6.897e-06 max_abs_ref 1.888e+01
abs err <= 1e-4: ok
gate passed (SC=48 SH=6 SW=10 SG=6 SNH=3 SLC=11 SCC=20 SFI=40 REPS=2)
```

## What this shows
- A whole SDXL-style SpatialTransformer block (GroupNorm, linear proj_in, LayerNorm + self-attention, LayerNorm + cross-attention over a context, LayerNorm + GEGLU feed-forward, linear proj_out, residuals) runs as one Kotoba program built by `bin/amu`, with a checksum equal to its C twin's bit for bit, on two shapes (one with a non-power-of-two head count and different token and context lengths).
- Reading the cross-attention keys from the image tokens instead of the context changes the checksum, so the gate can fail.
- The f32 output is within 3.8e-6 and 6.9e-6 of the f64 reference (libm exp and erf, so the twin's polynomial exp and A&S erf are inside that error); outputs reach about 19.
- Kotoba is roughly 5 to 10 times slower than `cc -O2` where timing is above noise.

## What it does not show
- Synthetic weights, one thread, CPU only; no f16, no checkpoint, no GPU. One transformer layer (depth 1), linear proj_in/out only (no 1x1-conv variant).
- The reference is the twin's own pipeline in double, not comfyui-clj or ComfyUI output.
- The kernels are copied into the program, not imported.
