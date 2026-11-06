# The SDXL UNet graph on the Apple GPU, gated against ComfyUI's own UNetModel (stage M slice 17, JUN-927)

Recorded 2026-11-06T19:04:35+0900 on 21mbp.local, M1 Max, load average 27.64 42.72 36.98. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, profile `amu-program-metal`. Command: `sh bench/kernels/unet-sdxl-named/run.sh <amu> <ComfyUI-0.44.0> <stubs> <python with torch, safetensors, einops, numpy>`.

## What it is
Until now the SDXL-shaped UNet (slices 11 and 12) was timed with synthetic weights and its wiring was never compared with anything. This slice adds a program that runs the **same topology with real, named weights** and checks every stage against ComfyUI v0.44.0's own `UNetModel`, built with `supported_models.SDXL`'s configuration (channel_mult 1 2 4, two ResBlocks per level, transformer depths 0 / 2 / 10, depth 10 in the middle block, output depths 0 0 0 2 2 2 10 10 10, linear projections, context 77 x 2,048, head size 64, label embedding of the 2,816-wide vector) but with **model_channels 64 instead of 320**: widths 64 / 128 / 256, 158 M parameters (the 2,048-wide context projections dominate), 1,680 tensors, random weights, float32 on the CPU. The program reads the ComfyUI tensor names from a safetensors file (`input_blocks.N.0`, `.1`, `middle_block.N`, `output_blocks.N.M`, `time_embed`, `label_emb.0.0/0.2`, `out.0/2`), converts the weights to half on the GPU, and runs the graph in one command buffer of 2,074 dispatches.

## Result (limits fixed in slice 10, before this program existed: final 1e-2, every stage 2e-2; |gpu - ref| / max(1, |ref|))
```
MC=64 latent 32x32 (256 px, 158058244 parameters, 2074 dispatches): emb=3.00e-06 h0=7.75e-04 h1=1.04e-03 h2=1.22e-03 h3=7.29e-04 h4=2.46e-03 h5=3.58e-03 h6=1.79e-03 h7=4.57e-03 h8=6.77e-03 m=8.31e-03 o0=7.24e-03 o1=6.28e-03 o2=3.32e-03 o3=4.20e-03 o4=4.09e-03 o5=2.27e-03 o6=2.30e-03 o7=1.94e-03 o8=1.39e-03 final=1.15e-03  finite=1 ok best=95.3 ms
MC=64 latent 64x64 (512 px, 158058244 parameters, 2074 dispatches): emb=3.33e-06 h0=8.80e-04 h1=1.10e-03 h2=1.51e-03 h3=8.77e-04 h4=2.50e-03 h5=4.13e-03 h6=2.34e-03 h7=5.78e-03 h8=7.31e-03 m=8.95e-03 o0=7.55e-03 o1=6.87e-03 o2=3.06e-03 o3=4.35e-03 o4=4.63e-03 o5=2.44e-03 o6=2.22e-03 o7=1.83e-03 o8=1.56e-03 final=1.21e-03  finite=1 ok best=114.5 ms
strong fixture, clean build (informational, deep stages grow with the larger activations): emb=3.00e-06 h0=7.75e-04 h1=1.04e-03 h2=1.22e-03 h3=7.29e-04 h4=4.73e-03 h5=6.96e-03 h6=4.17e-03 h7=1.56e-02 h8=2.63e-02 m=3.80e-02 o0=3.28e-02 o1=2.86e-02 o2=1.55e-02 o3=1.40e-02 o4=1.52e-02 o5=6.50e-03 o6=4.47e-03 o7=2.63e-03 o8=2.10e-03 final=1.28e-03  finite=1
mutant [skip-offset]: final error 5.62e-01 (detected)
mutant [middle-depth]: final error 1.07e-01 (detected)
mutant [label-embedding]: final error 3.71e-01 (detected)
mutant [cross-keys]: final error 1.07e-01 (detected)
gate passed
```
- Every one of the 21 stages (embedding, nine input blocks, middle block, nine output blocks, output) agrees at 256 px and at 512 px; the worst stage is the middle block at 8.3e-3 / 9.0e-3, the final output 1.2e-3 / 1.2e-3.
- The "strong" fixture (output projections of every attention and feed-forward multiplied by 3) exists only for the mutants. With the plain fixture, a middle block one transformer layer short or cross-attention keys taken from the value weights moved the final output by 2.5e-2 and 1.9e-2, under the 5e-2 bar fixed beforehand. Those two mutants were then run against the strong fixture, where they move it by 0.11 each; the clean build on that fixture is shown for reference (its deep stages reach 3.8e-2 in the middle block, above the 2e-2 stage limit: f16 error grows with the larger activations, and the limit was not applied there; its final output stays at 1.3e-3).
- **A bug found in the timing program of slice 11**: it stored the second skip tensor of the 320-wide level at byte offset 320 x HW instead of 640 x HW inside the 960-channel concatenation buffer (overlapping the upsampled tensor). It cost nothing in time and nothing was claimed about its values, but its wiring was not what the SDXL graph does; the named program found the right offset (and the mutant that puts it back is detected: final error 0.56). The timing program is fixed (the same dispatches and sizes; re-run: 0.71 s at 512 px, 2.1 s at 768 px, 4.1 s at 1024 px, 2,100 kernels, outputs finite, on a host with a load average of 20 to 46).

## What it does not show
- Not SDXL's real widths: model_channels 64, not 320. The topology, depths, context and head size are SDXL's; the channel widths are 5x smaller, so no layer here has the 1,280-wide convolutions or the K = 5,120 feed-forward of the real model, and the widest level has 4 attention heads of 64 where SDXL has 20. A full-size check needs the 2.57 B-parameter file (10 GB in float32, about 5 GB of the 27 GB free disk) and a 794 to 3,381 GMAC float32 forward on the CPU per resolution; it was not done.
- Random weights: the trained model's value ranges are not exercised. No trained checkpoint was read.
- Timing at SDXL widths remains the slice-11/12 measurement (synthetic weights).
- The 77 context tokens are padded to 128 rows of zeros; ComfyUI's own padding and the L+G concatenation that makes the 2,048-wide context are not part of this program (the context is taken from a file).
