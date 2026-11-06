# Normalisation, activation and data-movement kernels on the Apple GPU (stage M slice 5, JUN-927)

Recorded 2026-11-06T16:58:16+0900 on 21mbp.local, Apple M1 Max, load average 10.25 10.05 10.19 (busy host: timings are diagnostic).

- amu: `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, `bin/amu` sha256 `3839d2616ba737f59835cb17bb58d79543c99098da73ffe17a67a7039427dd7c`, profile `amu-program-metal` (ADR 0405)
- cc: Apple clang version 21.0.0 (clang-2100.1.1.101)
- command: `sh bench/kernels/ops-metal/run.sh <amu checkout>`

```
groupnorm_silu max_err=2.185e-07 (limit 0.0001) ok best_ns=279916 GB/s=1.0
layernorm max_err=2.387e-07 (limit 0.0001) ok best_ns=223791 GB/s=2.1
geglu max_err=9.347e-07 (limit 0.00001) ok best_ns=214166 GB/s=1.1
upsample max_err=0.000e+00 (limit 0) ok best_ns=215000 GB/s=2.8
timestep max_err=6.899e-05 (limit 0.001) ok best_ns=238166 GB/s=.3
add max_err=0.000e+00 (limit 0) ok best_ns=220708 GB/s=5.4
mutant [groupnorm_channel]: error 1.840e+00 (detected)
mutant [layernorm_affine]: error 2.421e+00 (detected)
mutant [geglu_gate]: error 1.624e+01 (detected)
mutant [upsample_index]: error 5.000e+00 (detected)
mutant [timestep_swap]: error 1.414e+00 (detected)
mutant [add_operand]: error 1.000e+30 (detected)
gate passed
```

## What this shows
- The rest of an SDXL block's non-matrix work now runs on the GPU as Kotoba programs built by `bin/amu` (no C loader or C source on the path; the double-precision reference is a test-time oracle): GroupNorm (affine) + SiLU on 64 channels x 576 positions in 8 groups, LayerNorm over 640 for 96 rows, GEGLU (erf by Abramowitz-Stegun 7.1.26) over 64 rows of 320, a nearest 2x upsample of 32 x 24 x 40, the sinusoidal timestep embedding (dim 320, 64 steps, t up to 1008 rad) and a residual add. The reductions use `simd_sum` and threadgroup memory, one threadgroup per group or row.
- Errors against a double-precision reference, |gpu - ref| / max(1, |ref|): GroupNorm+SiLU 2.2e-7, LayerNorm 2.4e-7, GEGLU 9.3e-7, timestep embedding 6.9e-5 (the float32 argument t * freq reaches about 1000 rad and carries its own rounding), upsample and add exactly 0.
- Six mutants are detected (error > 1e-2): GroupNorm's per-channel affine replaced by per-group, LayerNorm's gamma and beta swapped, GEGLU's value and gate taken from the same half, the upsample source row divided by 3, the timestep's cos and sin swapped, the add reading the wrong operand (the last one reads past a 4-element buffer, so its error shows as NaN, not a clean number).

## What it does not show
- Timings here are about 0.2 ms for every case: that is the command-buffer floor, not the kernels. The sizes are small, so no bandwidth figure is meaningful (the GB/s column is bytes over a floor-bound time). What these kernels cost in a UNet call depends on how many dispatches share a command buffer, which is the next slice.
- Float32 in, float32 out only; no f16 activations, no fusion with the neighbouring convolution or attention (GroupNorm+SiLU and the residual add are separate dispatches here). Inputs are small multiples of 1/16, 1/64, 1/256, not trained activations.
- Hand-written MSL in a Kotoba string, not emitted from KIR v2. M1 Max only. Not a CI step (needs a GPU host). The dump is written to stderr and split by byte count.
