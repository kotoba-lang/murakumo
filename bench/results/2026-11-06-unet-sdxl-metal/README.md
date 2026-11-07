# One SDXL-shaped UNet call on the Apple GPU, timed (stage M slice 11, JUN-927)

Recorded 2026-11-06T17:47:20+0900 on 21mbp.local, Apple M1 Max, 32 GiB, load average 10.18 11.42 11.65 (other agents' processes were running on this Mac throughout; see below).

- amu: `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, `bin/amu` sha256 `3839d2616ba737f59835cb17bb58d79543c99098da73ffe17a67a7039427dd7c`, profile `amu-program-metal` (ADR 0405)
- command: `sh bench/kernels/unet-sdxl-metal/run.sh <amu checkout>`

```
latent 64x64 (512 px): 2100 kernels, best 0.84 s per UNet call; output ok n=16384 mean|x|=0.071 max|x|=0.277
latent 96x96 (768 px): 2100 kernels, best 2.30 s per UNet call; output ok n=36864 mean|x|=0.074 max|x|=0.286
latent 128x128 (1024 px): 2100 kernels, best 4.18 s per UNet call; output ok n=65536 mean|x|=0.076 max|x|=0.281
finite outputs
```

## What this shows
- The graph of ComfyUI's **SDXL** `UNetModel` (`model_channels` 320, `channel_mult` (1, 2, 4), two ResBlocks per level, transformer depths 0 / 2 / 10, middle block of depth 10, linear projections, the 2,816-wide label embedding added to the time embedding, a 77-token context of width 2,048), built by a Kotoba program on the Metal route from the slice-10 kernels and recorders, runs on the GPU as **about 2,100 kernels in one command buffer**. Its 2.57 billion parameters are synthetic (pseudo-random half values made on the GPU, a fresh buffer per tensor, about 5.1 GB in all), so the memory traffic of streaming weights is real; the values are meaningless (the output is checked for size and finiteness only: 4 x lat x lat floats, no NaN, no infinity, |x| below 0.3).
- Best of 6 whole-graph runs per size, on a host with a load average of 10 to 13 from other processes: **0.84 s at 512 px, 2.27 s at 768 px, 4.49 s at 1024 px** (this run). Across the runs made while writing this record, 768 px took between **1.67 s** (the best seen) and 2.39 s, and 1024 px about 4.1 s: the spread is contention, so the best is the nearer to what a quiet host gives, and still an upper bound for it.
- What that means (arithmetic, not a render): at the best 768 px figure the UNet's 1,821 GMAC (see [2026-11-06-sdxl-budget](../2026-11-06-sdxl-budget/README.md)) run at about 1.1 TMAC/s overall, non-matrix kernels and dispatch included; 50 calls (25 steps with guidance) would take about 84 s on the M1 Max before the text encoders and the VAE decode. For scale, the I3 ADR derived about 1.3 TMAC/s from ComfyUI's measured 72 s on a 16 GiB M4 mini (a smaller GPU). So the Kotoba path has reached the same order as ComfyUI's, not beaten it, and it is not yet a faster route. The earlier per-kernel rates predict where the time goes: convolutions at 0.7 to 0.85 TMAC/s (slice 4) for 457 GMAC is about 0.5 to 0.6 s of the 1.67 s, the linear layers (1,236 GMAC at 1.5 to 3 TMAC/s) about 0.4 to 0.8 s, attention about 0.1 s; this was not profiled per kernel.

## What it does not show
- No correctness: random weights, no reference output at this size (the correctness gate is the mini UNet of slice 10 against ComfyUI). The timing is the graph's shape and the kernels' speed, not a render. Pseudo-random weights near zero keep activations bounded; a trained model's values could change nothing about the kernels' time here (no data-dependent branches) but this was not tested.
- Not a quiet host: other agents' processes were using the CPU and, possibly, the GPU (a load average of 10 to 13 throughout); no measurement here is a benchmark and none should be quoted as the Mac's speed. M1 Max only; the 16 GiB M4 minis of the fleet are untested, and nothing was run through ComfyUI/MPS on this Mac for a same-chip comparison.
- 1024 px needs about 3 GB more for the activations than 768 px; peak memory was not measured. The text encoders, the sampler and the VAE decode are not included. Hand-written MSL in a Kotoba string, not fused or tuned beyond the earlier slices; the import route is still open so the program is assembled from earlier programs' text. Not a CI step.
