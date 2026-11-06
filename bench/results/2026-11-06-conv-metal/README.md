# Implicit-GEMM 3x3 convolution on the Apple GPU from Amu selfhost (stage M slice 2, JUN-927)

Recorded 2026-11-06T16:39:34+0900 on 21mbp.local, Apple M1 Max, load average 8.71 9.77 12.42 (busy host: rates are diagnostic).

- amu: `74c45ab7d9d3c0ddd4c6d565887558b49b5c29b2`, `bin/amu` sha256 `ee9a94e406f90dd1c899121708bed01b44818f2c3bc0b9780f266b0408c61ff0`, profile `amu-program-metal` (ADR 0405)
- cc: Apple clang version 21.0.0 (clang-2100.1.1.101)
- command: `sh bench/kernels/conv-metal/run.sh <amu checkout>`

```
CIN=32 COUT=64 32x32 vals=-64..63 twin=79829374783033 metal=79829374783033 equal=yes best_ns=426291 GMAC/s=44.2
mutant (wrong row offset) differs: 113458615780793 != 79829374783033
CIN=64 COUT=128 24x40 vals=-64..63 twin=199691596089401 metal=199691596089401 equal=yes best_ns=714333 GMAC/s=99.0
CIN=320 COUT=320 48x48 vals=-8..7 twin=268901487743033 metal=268901487743033 equal=yes best_ns=1292958 GMAC/s=1642.2
CIN=640 COUT=640 24x24 vals=-8..7 twin=101765398032441 metal=101765398032441 equal=yes best_ns=1946500 GMAC/s=1090.8
CIN=640 COUT=640 48x48 vals=-8..7 twin=6997978128441 metal=6997978128441 equal=yes best_ns=4506458 GMAC/s=1884.7
gate passed
```

## What this shows
- A 3 x 3 convolution (stride 1, padding 1, NCHW, batch 1, no bias) runs on the GPU's matrix units as an implicit GEMM: each threadgroup gathers its 32 x 64 slice of the im2col matrix (zero outside the image) into threadgroup memory, loads the matching 64 x 32 weight slice, and four simdgroups multiply them (half inputs, float accumulators). The im2col matrix is never materialised. Built by `bin/amu` as a Kotoba program, no C loader or C source on the path (the C twin is a test-time oracle).
- The checksum of the float32 results equals an integer C twin's, bit for bit, on all five shapes, including 640 -> 640 channels on a 48 x 48 image. The data are small integers, so every product and partial sum is exact in float32 and the answer cannot depend on the order the matrix units add in: the gate tests the gather, the padding, the indexing and the tiling, not rounding. The three SDXL-like shapes use values -8..7 so their long sums (K up to 5,760) stay exact.
- A kernel whose row offset in the padding test is wrong gives a different checksum.
- Best-of-20 rates, command-buffer floor included, host load average 17 to 20: about 1.7 TMAC/s (320 -> 320 at 48 x 48), 1.1 TMAC/s (640 -> 640 at 24 x 24, a small image) and 1.9 TMAC/s (640 -> 640 at 48 x 48), on an M1 Max. The plain GEMM of slice 1 reached 3.1 to 3.6 TMAC/s on this chip.

## What it does not show
- Only the 3 x 3, stride 1 case with CIN a multiple of 32, COUT a multiple of 64 and H x W a multiple of 64. SDXL's first convolution (4 input channels), its last (4 output channels), the stride-2 downsamples and the 1 x 1 skips are not covered. No bias, no activation or residual epilogue, NCHW only (no NHWC).
- Integer-valued f16 inputs: no f16 rounding, no model weights, no accuracy claim. One chip (M1 Max), not the 16 GiB M4 minis.
- Hand-written MSL in a Kotoba string, not emitted from KIR v2; a plain gather into threadgroup memory without double buffering, so the 1.1 to 1.9 TMAC/s is a first figure, not a tuned one.
- Not a CI step (needs a GPU host).
