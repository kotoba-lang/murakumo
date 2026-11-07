# Fused attention on the Apple GPU from Amu selfhost (stage M slice 3, JUN-927)

Recorded 2026-11-06T16:46:45+0900 on 21mbp.local, Apple M1 Max, load average 7.97 8.14 10.54 (busy host: rates are diagnostic).

- amu: `74c45ab7d9d3c0ddd4c6d565887558b49b5c29b2`, `bin/amu` sha256 `ee9a94e406f90dd1c899121708bed01b44818f2c3bc0b9780f266b0408c61ff0`, profile `amu-program-metal` (ADR 0405)
- cc: Apple clang version 21.0.0 (clang-2100.1.1.101)
- command: `sh bench/kernels/attention-metal/run.sh <amu checkout>`

```
LQ=256 LK=256 heads=4 max_abs_err=2.414e-04 max_abs_ref=9.999e-01 within_0.002=yes best_ns=426125 GMAC/s=78.7
LQ=256 LK=77 heads=4 max_abs_err=2.382e-04 max_abs_ref=9.999e-01 within_0.002=yes best_ns=348291 GMAC/s=28.9
mutant [constant float SCALE = 0.125f;]: error 1.278e-01 > 0.05 (detected)
mutant [(kb * 32u + c < LK) ? exp(Ssm[idx]]: error 9.517e+03 > 0.05 (detected)
LQ=576 LK=576 heads=20 max_abs_err=3.053e-04 max_abs_ref=9.998e-01 within_0.002=yes best_ns=957958 GMAC/s=886.6
LQ=2304 LK=2304 heads=10 max_abs_err=2.755e-04 max_abs_ref=9.998e-01 within_0.002=yes best_ns=4931875 GMAC/s=1377.7
LQ=2304 LK=77 heads=10 max_abs_err=2.436e-04 max_abs_ref=9.998e-01 within_0.002=yes best_ns=523875 GMAC/s=433.4
gate passed
```

## What this shows
- Non-causal multi-head scaled dot-product attention (head dim 64, token-major Q/K/V, self- and cross-attention shapes) runs as one fused kernel on the GPU's matrix units, built by `bin/amu` as a Kotoba program with no C loader or C source on the path (the double-precision reference is a test-time oracle). A threadgroup of four simdgroups takes 32 queries of one head and makes two passes over the keys in blocks of 32: pass 1 computes S = Q K^T / 8 on the matrix units and keeps a running row maximum and sum of exp in threadgroup memory; pass 2 recomputes S, forms P = exp(s - max) / sum in half and accumulates O += P V on the matrix units. Keys beyond LK (the last block is padded to 32) are masked.
- Output error against a double-precision reference on the same inputs is 2.4e-4 to 3.1e-4 on outputs of magnitude up to 1.0, under a 2e-3 bound, on five shapes including the SDXL-like 2304 x 2304 x 10 heads, 576 x 576 x 20 heads, and cross-attention against 77 keys (not a multiple of 32).
- Two mutants are detected: the score scale changed from 1/8 to 1 (error 0.13) and the padding mask removed (error 9.5e3).
- Best-of-20 rates counting the two GEMMs (the second pass's recomputation of S is not counted), command-buffer floor included, host load average 17 to 20, M1 Max: about 1.38 TMAC/s at 2304 x 2304 with 10 heads, 0.84 TMAC/s at 576 x 576 with 20 heads, 0.41 TMAC/s for 2304 queries against 77 keys. The ADR's count puts attention at about 128 of a UNet call's 1,821 GMAC at 768 px.

## What it does not show
- Approximate by construction, not bit-exact: exp is the GPU's, P is rounded to half, and the bound (2e-3 on outputs <= 1) is mine. Inputs are small integers over 32 or 64, exact in half; real model activations, real f16 rounding and the large-magnitude scores of trained weights are not exercised.
- Head dim 64 only, no mask or bias input, no causal variant, no relative position; LQ must be a multiple of 32. The per-row softmax statistics are computed by one thread per row (32 of 128 threads), a first figure, not a tuned one.
- One chip (M1 Max), not the 16 GiB M4 minis. Hand-written MSL in a Kotoba string, not emitted from KIR v2. Not a CI step (needs a GPU host). The comparison script writes the dump to stderr and splits it by byte count, so a program that also printed other text on stderr would break the split.
