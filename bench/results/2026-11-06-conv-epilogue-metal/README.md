# Generalised implicit-GEMM convolution with epilogues on the Apple GPU (stage M slice 4, JUN-927)

Recorded 2026-11-06T16:55:22+0900 on 21mbp.local, Apple M1 Max, load average 12.26 10.53 10.39 (busy host: rates are diagnostic).

- amu: `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, `bin/amu` sha256 `3839d2616ba737f59835cb17bb58d79543c99098da73ffe17a67a7039427dd7c`, profile `amu-program-metal` (ADR 0405)
- cc: Apple clang version 21.0.0 (clang-2100.1.1.101)
- command: `sh bench/kernels/conv-epilogue-metal/run.sh <amu checkout>`

```
CIN=32 COUT=64 32x32 k3 s1 mode0 max_err=0.000e+00 (limit 0) ok best_ns=442083 GMAC/s=42.6
CIN=32 COUT=64 32x32 k3 s1 mode1 max_err=0.000e+00 (limit 0) ok best_ns=451625 GMAC/s=41.7
CIN=64 COUT=128 24x40 k1 s1 mode2 max_err=0.000e+00 (limit 0) ok best_ns=282250 GMAC/s=27.8
CIN=64 COUT=128 32x32 k3 s2 mode1 max_err=0.000e+00 (limit 0) ok best_ns=666166 GMAC/s=28.3
CIN=4 COUT=64 48x48 k3 s1 mode1 max_err=0.000e+00 (limit 0) ok best_ns=248750 GMAC/s=21.3
CIN=64 COUT=4 48x48 k3 s1 mode1 max_err=0.000e+00 (limit 0) ok best_ns=634000 GMAC/s=8.3
CIN=32 COUT=64 10x10 k3 s1 mode3 max_err=7.922e-08 (limit 0.0001) ok best_ns=475041 GMAC/s=3.8
mutant [bias]: error 6.900e+01 (detected)
mutant [stride]: error 6.418e+04 (detected)
mutant [residual]: error 8.900e+01 (detected)
CIN=320 COUT=320 48x48 k3 s1 mode3 max_err=7.922e-08 (limit 0.0001) ok best_ns=2480125 GMAC/s=856.1
CIN=640 COUT=640 48x48 k3 s1 mode1 max_err=0.000e+00 (limit 0) ok best_ns=10072083 GMAC/s=843.2
CIN=640 COUT=640 48x48 k3 s2 mode1 max_err=0.000e+00 (limit 0) ok best_ns=3442708 GMAC/s=616.7
gate passed
```

## What this shows
- The slice-2 convolution kernel, generalised and given epilogues, built by `bin/amu` as a Kotoba program (no C loader or C source on the path; the integer reference is a test-time oracle): window 1 x 1 or 3 x 3, stride 1 or 2, padding window/2, any channel counts (K is padded to 32 with zeros, output rows beyond COUT and columns beyond the position count are masked), and an epilogue run on the float32 accumulators in threadgroup memory before the store: none, + bias, + bias + residual (a skip connection), or SiLU(+ bias).
- It covers the cases slice 2 left out: the 1 x 1 skip with a residual add (64 -> 128 on 24 x 40), the stride-2 downsample (64 -> 128, and 640 -> 640 on 48 x 48), SDXL's 4-channel first convolution (4 -> 64 on 48 x 48) and 4-channel last convolution (64 -> 4), and a position count that is not a multiple of 64 (10 x 10).
- Modes 0 to 2 equal the exact integer reference with error exactly 0 on every shape (small-integer data keeps all sums exact in float32); the SiLU mode is within 7.9e-8 (|gpu - ref| / max(1, |ref|)). Three mutants are detected: the bias index dropped, the stride dropped from the row coordinate, the residual indexed without the channel.
- Best-of-20 rates, command-buffer floor included, host load average 17 to 20, M1 Max: about 0.85 TMAC/s for 320 -> 320 and 640 -> 640 at 48 x 48 with stride 1, 0.66 TMAC/s for the 640 -> 640 stride-2 downsample.

## What it does not show
- This kernel is slower than slice 2's (about 1.7 to 1.9 TMAC/s on the same 320 and 640 channel shapes). The epilogue stages the 64 x 64 tile through 16 KB of extra threadgroup memory and every load carries a bounds test; skipping the tests when the shape divides evenly (the `EVEN` constant) recovered only about 5 percent. It is a first figure, not tuned: no double buffering, no function-constant specialisation, no split of the even and ragged cases into separate kernels.
- Integer-valued f16 inputs: no f16 rounding, no model weights, no accuracy claim for model data. NCHW only, batch 1. One chip (M1 Max), not the 16 GiB M4 minis. Hand-written MSL in a Kotoba string, not emitted from KIR v2. Not a CI step (needs a GPU host).
- The dump is written to stderr and split by byte count, so a program that also printed other text on stderr would break the split.
