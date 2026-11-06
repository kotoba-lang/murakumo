# f16 GEMM on data that are not small integers (stage M slice 7, JUN-927)

Recorded 2026-11-06T17:05:45+0900 on 21mbp.local, Apple M1 Max, load average 14.71 14.44 12.45 (busy host: rates are diagnostic).

- amu: `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, `bin/amu` sha256 `3839d2616ba737f59835cb17bb58d79543c99098da73ffe17a67a7039427dd7c`, profile `amu-program-metal` (ADR 0405)
- cc: Apple clang version 21.0.0 (clang-2100.1.1.101)
- command: `sh bench/kernels/gemm-f16-metal/run.sh <amu checkout>`

```
M=512 K=512 N=640 err_vs_half=1.294e-05 (limit 1.22e-04) err_vs_unrounded=8.500e-03 (limit 2.21e-02) ok best_ns=417208 GMAC/s=402.1
M=2304 K=640 N=640 err_vs_half=2.097e-05 (limit 1.53e-04) err_vs_unrounded=1.157e-02 (limit 2.47e-02) ok best_ns=566250 GMAC/s=1666.6
mutant (wrong B stride): error 3.081e+01 (detected)
gate passed
```

## What this shows
- Slice 1's GEMM, run on data that exercise rounding: A and B are 15-bit integers s in [-16384, 16383] (a uniform draw) made on the GPU into half as `half(s) * 2^-14`, so values in [-1, 1) are rounded to 11 significant bits (round to nearest even) by the GPU's own conversion, then multiplied on the matrix units with float32 accumulation. A double-precision reference computes C two ways from the same draws.
- **Accumulation only** (reference built from the half-rounded values): the GPU is within 1.3e-5 (K = 512) and 2.1e-5 (K = 640) of double, measured as |gpu - ref| / max(1, |ref|) over every output. That is the float32 accumulation error of 512 to 640 products of size about 0.1, about 40 to 50 times smaller than the limit 4 * K * 2^-24.
- **f16 storage as well** (reference built from the unrounded values): the error is 8.5e-3 (K = 512) and 1.16e-2 (K = 640), about 1 percent of an output of order 1. It is dominated by rounding the inputs to half, scales like 2^-11 * sqrt(K), and is the size of the gap a half-precision pipeline carries against an exact one for random data of this magnitude.
- A mutant that reads B with the wrong row stride is detected (error 31).

## What it does not show
- The limits are 4 * K * 2^-24 (accumulation) and 2 * 2^-11 * sqrt(K) (storage), written down after a first run showed my first guesses (1e-5 and 5e-3) were tighter than this arithmetic allows; they come from the scale of the error terms, not from tuning to the measurements.
- Uniform random inputs, not trained weights or activations: real layers have different magnitudes, outliers and correlated errors, so the ~1 percent is a scale, not a prediction for a UNet. The I3 tolerance for a real checkpoint must be set from ComfyUI's own fp16-versus-fp32 deviation, as the ADR says.
- GEMM only (convolution and attention use the same accumulate-in-float32 matrix units but were not repeated on non-integer data here). The reference runs only up to K = 640 because the double-precision loops are slow. M1 Max only. Not a CI step (needs a GPU host and a cc with `_Float16`).
