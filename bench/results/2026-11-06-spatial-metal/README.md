# An SDXL-style SpatialTransformer block end to end on the Apple GPU (stage M slice 9, JUN-927)

Recorded 2026-11-06T17:13:19+0900 on 21mbp.local, Apple M1 Max, load average 16.11 14.82 13.27 (busy host: timing is diagnostic).

- amu: `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, `bin/amu` sha256 `3839d2616ba737f59835cb17bb58d79543c99098da73ffe17a67a7039427dd7c`, profile `amu-program-metal` (ADR 0405)
- cc: Apple clang version 21.0.0 (clang-2100.1.1.101)
- command: `sh bench/kernels/spatial-metal/run.sh <amu checkout>`

```
SpatialTransformer 128ch 8x8, 2 heads, 16 ctx tokens: gpu_err=5.562e-02 (limit 0.1) cpu_f32_err=3.748e-05 ok best=1.14 ms for 21 dispatches
mutant [crosskeys]: error 4.224e+01 (detected)
mutant [residual]: error 3.717e+01 (detected)
mutant [gegluGate]: error 1.371e+02 (detected)
mutant [lnAffine]: error 1.262e+02 (detected)
gate passed
```

## What this shows
- A whole SpatialTransformer block (GroupNorm into token layout, linear proj_in, LayerNorm + self-attention, LayerNorm + cross-attention over a context, LayerNorm + GEGLU feed-forward, linear proj_out, residuals) runs on the GPU from one Kotoba program built by `bin/amu`: **21 kernels in one command buffer and one encoder**, each reading what the previous wrote. The residual stream stays float32; the matrix units read half (LayerNorm, the projections, GEGLU and the attention output write half where a matrix operand comes next). 128 channels on 8 x 8 (64 tokens), 2 heads of 64, 32 GroupNorm groups, 16 context tokens of width 24 (padded to 64 rows), feed-forward width 512.
- Same parameters as the CPU SpatialTransformer of I2 (`bench/kernels/spatial`: the same LCG stream, order and scales), converted on the GPU. Against the double-precision block from `spatial/impl.h` the GPU is within **5.6e-2** (|gpu - ref| / max(1, |ref|)) at its worst output; the outputs reach about 100, so that is about 5e-4 of the largest value, and an ad hoc run of the same comparison (not in the script) gave a mean of 1.6e-3. The f32 CPU twin is within 3.7e-5 of the same reference. The difference is half-precision matrix operands through 21 stages, not a disagreement between kernels.
- Four mutants are detected (error > 0.5): cross-attention keys taken from the image tokens (42), the residual of the attention output dropped (37), GEGLU reading the gate as the value (137), LayerNorm gamma and beta swapped (126).
- The 21 dispatches take about 1.1 ms in all (best of 20, host load average 17 to 20, M1 Max): 53 microseconds each on average, in line with the batched-dispatch figures of slice 6 plus the kernels' own work.

## What it does not show
- One small block, one shape, synthetic uniform weights, not a trained layer. The limit (0.1) is about twice the 5.6e-2 the first run gave: it records the observed scale and is not an independent prediction.
- Depth 1, linear proj_in/out only, head dimension 64, context padded to a multiple of 64 rows by zeros; the kernels are the earlier slices' with the shape parameters read from small buffers; no fusion between them, no tuning. 1.1 ms for about 0.05 GMAC says nothing about the matrix units' rate at UNet sizes.
- Not compared with ComfyUI's SpatialTransformer on the GPU path (the I2 comparison was twin versus ComfyUI in float32 on CPU, 3.6e-6). M1 Max only; the dump goes to stderr and is split by byte count. Not a CI step (needs a GPU host).
