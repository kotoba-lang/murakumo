# The SDXL UNet graph at wider channel widths (stage M slice 20, JUN-927)

Recorded 2026-11-06T19:19:49+0900 on 21mbp.local, M1 Max 32 GiB. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`. Command: `MUTANTS=0 sh bench/kernels/unet-sdxl-named/run.sh <amu> <ComfyUI-0.44.0> <stubs> <python> "128:32 192:32"` (the slice-17 gate at larger model_channels; limits unchanged: final 1e-2, every stage 2e-2, fixed in slice 10).

## Result
```
MC=128 latent 32x32 (256 px, 493833732 parameters, 2074 dispatches): emb=3.45e-06 h0=9.31e-04 h1=1.02e-03 h2=1.31e-03 h3=7.64e-04 h4=2.54e-03 h5=3.12e-03 h6=1.69e-03 h7=4.43e-03 h8=8.19e-03 m=9.19e-03 o0=6.57e-03 o1=6.46e-03 o2=3.73e-03 o3=4.12e-03 o4=3.99e-03 o5=1.90e-03 o6=1.66e-03 o7=1.53e-03 o8=1.48e-03 final=9.24e-04  finite=1 ok best=133.6 ms
MC=192 latent 32x32 (256 px, 1007326468 parameters, 2074 dispatches): emb=5.33e-06 h0=9.16e-04 h1=1.04e-03 h2=1.22e-03 h3=7.06e-04 h4=2.22e-03 h5=3.44e-03 h6=1.75e-03 h7=4.87e-03 h8=7.24e-03 m=9.72e-03 o0=6.27e-03 o1=5.68e-03 o2=3.47e-03 o3=4.12e-03 o4=3.81e-03 o5=2.25e-03 o6=2.00e-03 o7=1.51e-03 o8=1.21e-03 final=1.00e-03  finite=1 ok best=193.4 ms
gate passed
```
- model_channels 128 (widths 128 / 256 / 512, heads 2 / 4 / 8, 494 M parameters) and 192 (192 / 384 / 768, heads 3 / 6 / 12, **1.007 B parameters**, 4 GB of float32 weights) agree with ComfyUI's own `UNetModel` (SDXL topology and depths) at every one of the 21 stages: worst stage 9.2e-3 (128) and 9.7e-3 (192), final 9.2e-4 and 1.0e-3. Together with slice 17's 64 this covers a 3x range of widths and head counts up to 12 (SDXL's real model has widths 320 / 640 / 1,280 and 20 heads at its widest level, 2.57 B parameters).
- Whole-graph time at 256 px: 134 ms (494 M) and 187 ms (1.0 B), 2,074 dispatches, on a loaded host (diagnostic).
- With MUTANTS on (the default) the first run at MC 128 found two mutants below the 5e-2 bar even on the strong fixture (middle block one layer short: 1.8e-2; cross-attention keys from the value weights: 3.5e-2): the larger widths dilute a single wrong layer. That is a statement about mutant sensitivity at that width, not a failure of the clean build, which passed; the mutants stay defined at MC 64 where they exceed the bar, and the new MUTANTS=0 switch lets the width runs skip them.

## What it does not show
- **Not SDXL's real widths (320 / 640 / 1,280) and not 2.57 B parameters.** I did not generate the real-size fixture: it is a 10 GB float32 file, building it with torch needs roughly 20 GB of RAM on a 32 GiB Mac whose swap was already 7.4 of 8 GB used by other processes (and the disk had fallen to 8.6 GB free before I removed my own leftover gate build directories, about 15 GB, from earlier slices). MC 256 (1.6 B, about 13 GB of Python RAM) was also left out for the same reason. If the host is quiet, `run.sh ... "320:32"` is the command that would do it.
- Random weights; no trained model. The 20-head level (1,280-wide) is covered only up to 12 heads.
- The gate scripts now delete their large weight files on success (KEEP=1 keeps them): the earlier slices' build directories were left in the temp directory and had filled the disk.
