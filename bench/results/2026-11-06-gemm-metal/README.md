# f16 matrix-unit GEMM on the Apple GPU from Amu selfhost (stage M slice 1, JUN-927)

Recorded 2026-11-06T16:20:02+0900 on 21mbp.local, Apple M1 Max, 32 GiB, load average 11.63 16.27 18.91 (busy host: rates are diagnostic).

- amu: `f6c6ea215a8c51383c5ebac0b39879cef780eafb`, `bin/amu` sha256 `ee9a94e406f90dd1c899121708bed01b44818f2c3bc0b9780f266b0408c61ff0`, profile `amu-program-metal` (ADR 0405)
- cc: Apple clang version 21.0.0 (clang-2100.1.1.101)
- command: `sh bench/kernels/gemm-metal/run.sh <amu checkout>`

```
M=512 K=512 N=640 twin=128877095483449 metal=128877095483449 equal=yes best_ns=421500 GMAC/s=398.0
mutant (wrong B stride) differs: 62944517124153 != 128877095483449
M=2304 K=640 N=640 twin=104141361780729 metal=104141361780729 equal=yes best_ns=768166 GMAC/s=1228.5
M=2304 K=640 N=2560 twin=147039879339769 metal=147039879339769 equal=yes best_ns=1248583 GMAC/s=3023.3
M=4096 K=1024 N=4096 twin=180218851373113 metal=180218851373113 equal=yes best_ns=4803291 GMAC/s=3576.6
gate passed
```

## What this shows
- A Kotoba program built by `bin/amu` reaches the GPU's matrix units: a `simdgroup_matrix` kernel (half inputs, float accumulators, 64 x 64 output tile per 4-simdgroup threadgroup, operands loaded straight from device memory) computes C = A.B and the checksum of the float32 results equals an integer C twin's, bit for bit, on four shapes up to 4096 x 1024 x 4096. No C loader, no C source on the path (the C twin is a test-time oracle).
- The data are small integers so every product and partial sum is exact in float32 and the result cannot depend on the order the matrix units accumulate in; the gate therefore tests indexing, tiling and the use of the matrix instructions, not rounding. A kernel reading B with the wrong row stride gives a different checksum.
- Best-of-20 dispatch rates, command-buffer floor included, on a host at load average 17 to 20: about 3.1 TMAC/s at 2304 x 640 x 2560 and 3.6 TMAC/s at 4096 x 1024 x 4096 (M1 Max). The S7 slice's plain loop was about 0.05 TMAC/s.

## What it does not show
- A GEMM only. Convolution (implicit GEMM), attention (softmax between two GEMMs), norms, the activation functions and the many small dispatches of a UNet call are not measured; the ~275 us command-buffer floor from S7 applies to each unbatched dispatch.
- Integer-valued f16 inputs: no f16 rounding, no real weights, no accuracy claim for model data. The f16 storage question (a language type) is untouched: the data are made on the GPU by a short-to-half conversion kernel.
- One chip (M1 Max), not the 16 GiB M4 minis the fleet runs. Kernel is hand-written MSL in a Kotoba string, not emitted from KIR v2. Not a CI step (needs a GPU host).
- The 1.3 TMAC/s the ADR derives from ComfyUI's 72 s is an end-to-end figure on another chip; this is a GEMM-only rate on this one.
