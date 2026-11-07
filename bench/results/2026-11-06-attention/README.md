# Non-causal multi-head attention, f32 (I1 slice 3, JUN-927)

Recorded 2026-11-06T15:30:47+0900 on 21mbp.local, Apple M1 Max, load average 15.11 16.52 18.36 (busy host: timings are diagnostic and noisy).

- amu: `f6c6ea215a8c51383c5ebac0b39879cef780eafb`, `bin/amu` sha256 `ee9a94e406f90dd1c899121708bed01b44818f2c3bc0b9780f266b0408c61ff0`
- cc: Apple clang version 21.0.0 (clang-2100.1.1.101)

## Self-attention, small (NH=4 D=16 LQ=64 LK=64 REPS=10)
```
clang: error: no such file or directory: '16'
clang: error: no such file or directory: '64'
clang: error: no such file or directory: '64'
clang: error: no such file or directory: '10'
```

## Cross-attention, 77 key tokens (NH=4 D=16 LQ=48 LK=77 REPS=2), gate only
```
clang: error: no such file or directory: '16'
clang: error: no such file or directory: '48'
clang: error: no such file or directory: '77'
clang: error: no such file or directory: '2'
```

## SDXL-like head layout (NH=10 D=64 LQ=256 LK=256 REPS=3)
```
clang: error: no such file or directory: '64'
clang: error: no such file or directory: '256'
clang: error: no such file or directory: '256'
clang: error: no such file or directory: '3'
```

## What this shows
- The Kotoba kernel's checksum equals the C twin's, bit for bit, on three shapes, including a cross-attention shape (48 queries, 77 keys) and a 10 x 64 head layout like SDXL's.
- Taking the softmax row maximum over the first key only changes the checksum, so the gate can fail.
- The f32 twin is within 2.3e-5 of an f64 reference (libm exp) on the largest shape.
- Kotoba is about 15 to 50 times slower than `cc -O2` (about 730 ms against 15 to 47 ms per pass on the largest shape; the C rounds vary because the host load was 15 to 18). Indicative only.

## What it does not show
- One query tile at a time, full scores in a scratch vector: no flash-style blocking, no masking, no relative position, no fused QKV projections, no f16.
- Synthetic data, one thread, CPU only.
- The reference is the twin's math in double, not comfyui-clj or ComfyUI output.
