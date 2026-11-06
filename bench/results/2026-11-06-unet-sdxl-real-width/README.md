# SDXL's UNet at its real width: 2.57 billion parameters, gated against ComfyUI (stage M slice 25, JUN-927)

Recorded 2026-11-06T20:02:57+0900 on 21mbp.local, M1 Max 32 GiB, load average 10.55 7.78 8.30. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`. Command: `MUTANTS=0 sh bench/kernels/unet-sdxl-named/run.sh <amu> <ComfyUI-0.44.0> <stubs> <python> "320:32"`.

## Result (limits fixed in slice 10: final 1e-2, every stage 2e-2; |gpu - ref| / max(1, |ref|))
```
MC=320 latent 32x32 (256 px, 2567463684 parameters, 2074 dispatches): emb=5.13e-06 h0=8.01e-04 h1=1.15e-03 h2=1.34e-03 h3=8.41e-04 h4=2.22e-03 h5=3.41e-03 h6=1.82e-03 h7=4.71e-03 h8=7.58e-03 m=9.50e-03 o0=7.61e-03 o1=6.73e-03 o2=4.01e-03 o3=4.72e-03 o4=3.82e-03 o5=2.20e-03 o6=1.73e-03 o7=1.43e-03 o8=1.10e-03 final=8.22e-04  finite=1 ok best=400.0 ms
gate passed
```
- **model_channels 320, widths 320 / 640 / 1,280, heads up to 20, 2,567,463,684 parameters (the exact count of the SDXL UNet, as counted on the meta device in slice 8), 1,680 tensors, a 10.27 GB float32 file**: all 21 stages (embedding, nine input blocks, middle, nine output blocks, output) agree with ComfyUI v0.44.0's own `UNetModel` built from `supported_models.SDXL`'s configuration: worst stage 9.5e-3 (the middle block), final output 8.2e-4, at a 256 px latent (32 x 32). The whole graph takes 400 ms at that size with all weights converted from the float32 file on the GPU in the same command buffer (2,074 dispatches).
- This closes the gap left by slices 17 and 20 (64 to 192 channels): the topology, depths, head size and every width are now SDXL's own. Random weights, not a trained model.

## How it fitted in a 32 GiB Mac (the earlier slices said it would not)
The memory problem was the fixture, not the GPU run. The old fixture builder held the float32 model (10.3 GB), a copy for `save_file` and the CPU forward pass at once, about 20 GB. The new path (`model_channels >= 256` in make_fixture.py) writes the safetensors file tensor by tensor straight from a seeded generator and lets the CPU oracle use the file memory-mapped (clean page-cache pages): the fixture step peaks at **1.07 GB** of process memory and 56 seconds; the GPU program then reads the file into one 10.3 GB Metal buffer and converts to half on the GPU (5.2 GB more). During this run swap grew from 7.4 to 9.6 GB (macOS extends it on demand; other agents' processes were using the machine).

## What it does not show
- Only the 256 px size (latent 32) at real width. The SDXL-shaped timing program covers 512 to 1024 px at real width but with synthetic weights and no correctness check; a 512 px real-width gate would only need a longer CPU oracle (about 4x).
- The mutants were not run at this width (they bite at MC 64, and two fall below the 5e-2 bar at MC 128 and wider, slice 20). Random weights; no trained checkpoint (D1).
- The gate script deletes the 10 GB weight file on success (KEEP=1 keeps it).
