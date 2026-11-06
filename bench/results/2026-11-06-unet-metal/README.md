# ComfyUI's UNetModel (mini configuration) end to end on the Apple GPU (stage M slice 10, JUN-927)

Recorded 2026-11-06T17:37:13+0900 on 21mbp.local, Apple M1 Max, load average 16.06 13.07 11.99 (busy host: timing is diagnostic).

- amu: `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, `bin/amu` sha256 `3839d2616ba737f59835cb17bb58d79543c99098da73ffe17a67a7039427dd7c`, profile `amu-program-metal` (ADR 0405)
- oracle: ComfyUI v0.44.0's own `UNetModel` (tarball sha256 `7d796d74f42fe862eb55d1c217349fee92c2cac5d4ababe06c8b73f90c936dc0`, see [2026-11-06-comfyui-oracle](../2026-11-06-comfyui-oracle/README.md)), Python 3.11.13, torch 2.6.0, float32 on the CPU, with the stubs of `bench/oracle/comfyui/stubs`; a local oracle, not a route
- command: `sh bench/kernels/unet-metal/run.sh <amu checkout> <ComfyUI-0.44.0> <stubs> <python with torch> [python3]`

```
mini UNetModel on the GPU: emb=4.08e-06 h0=6.64e-04 b1=9.30e-04 d=4.84e-04 b3=2.19e-03 m=3.22e-03 o0=4.26e-03 o1=2.19e-03 o2=2.10e-03 o3=2.03e-03 final=1.37e-03
final=1.368e-03 worst stage=4.26e-03 (limits 0.01 / 0.02) ok best=7.87 ms for the whole graph
mutant [timeemb]: final error 1.984e-01 (detected)
mutant [upsample]: final error 9.627e-01 (detected)
mutant [residual]: final error 1.335e+00 (detected)
gate passed
```

## What this shows
- ComfyUI's own `UNetModel`, built in a mini configuration (4 -> 4 channels, `model_channels` 32, `channel_mult` (1, 2), one ResBlock per level, a SpatialTransformer of depth 1 at the second level, the middle block, four output blocks, linear projections, context width 24, a 16 x 16 latent, 981,348 parameters), runs end to end on the GPU from **one Kotoba program** built by `bin/amu`. The weights are read from a safetensors file under ComfyUI's own parameter names (`input_blocks.3.1.transformer_blocks.0.attn1.to_q.weight` and the rest): the program indexes and admits the header, reads the data region into a Metal buffer, finds each tensor by name and converts the matrices to half on the GPU.
- The graph is ComfyUI's: the sinusoidal timestep embedding and its two-layer MLP; `input_blocks.0` (conv), `.1` (ResBlock, identity skip), `.2` (Downsample, a stride-2 convolution), `.3` (ResBlock with a 1 x 1 skip + SpatialTransformer); the middle block (ResBlock, SpatialTransformer, ResBlock); `output_blocks.0` to `.3`, each a ResBlock over the channel concatenation of the running tensor and a skip (128, 96, 96 and 64 input channels), the first two followed by SpatialTransformers and the second by the Upsample (nearest 2x then a 3 x 3 convolution); the output head (GroupNorm, SiLU, convolution). The encoder writes each skip tensor straight into the second half of the decoder's concatenation buffer by a buffer offset, so concatenation moves no data. **All 219 kernels are encoded into one command buffer and one encoder.**
- Against ComfyUI's float32 CPU forward pass on the same weights, input latent, context and timestep, the final output is within **1.4e-3** (|gpu - ref| / max(1, |ref|); mean 2.8e-4; outputs up to 1.2). The activations after every block agree too, within 4.3e-3 at the worst (`o0`); the time embedding to 4e-6.
- Three mutants push the final error far above the 0.05 bar: the time embedding left out of the ResBlocks (0.20), the Upsample reading the wrong source row (0.96), the residual epilogue of the convolutions off (1.3). The 219 dispatches take about 7.9 ms (best of 20, host load average 17 to 20, M1 Max).

## What it does not show
- A mini model: 981 thousand parameters, 16 x 16 latent, depth-1 transformers, no class or `adm` conditioning, one transformer layer per block, attention heads of 64 only. Not SDXL: its widths (320 to 1280), depths (up to 10), 2,816-wide `y` embedding and the sizes at which the matrix units reach their rate are untested. Random weights, not a trained model. The limits (1e-2 final, 2e-2 per stage) are the f16 scale of the earlier slices, set before this slice's first run; the first run gave 1.4e-3 and 4.3e-3.
- The kernels are the earlier slices' (a few changed to read their shape from small parameter buffers and ComfyUI's [out, in] weight layout), not fused, not tuned; the timing says nothing about SDXL-size speed. The program is generated from the earlier programs' text because the import route is still open.
- A single timestep, batch 1, no classifier-free guidance pass, no sampler. The GPU is reached through the Metal profile, a platform route, not the browser-consent operation of the client-runtime contract. M1 Max only; the dump goes to stderr and is split by byte count. Not a CI step (needs a GPU host, Python with torch for the oracle).
