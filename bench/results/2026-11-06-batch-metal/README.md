# Command-buffer batching: what one dispatch costs (stage M slice 6, JUN-927)

Recorded 2026-11-06T17:01:06+0900 on 21mbp.local, Apple M1 Max, load average 15.28 12.82 11.31 (busy host: figures are diagnostic).

- amu: `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, `bin/amu` sha256 `3839d2616ba737f59835cb17bb58d79543c99098da73ffe17a67a7039427dd7c`, profile `amu-program-metal` (ADR 0405)
- command: `sh bench/kernels/batch-metal/run.sh <amu checkout>`; module-call count: `bench/oracle/comfyui/sdxl_dispatch_count.py` (ComfyUI v0.44.0 on the meta device, see [2026-11-06-sdxl-budget](../2026-11-06-sdxl-budget/README.md))

```
threads=4096 dispatches=400  mode0 254.1 us  mode1 5.07 us  mode2 8.51 us per dispatch  mode0/mode1 = 50x
threads=1048576 dispatches=400  mode0 321.4 us  mode1 19.68 us  mode2 23.54 us per dispatch  mode0/mode1 = 16x
gate passed (batching helps)
```

```
one SDXL UNet forward on ComfyUI v0.44.0 (meta device), module calls:
{'Linear': 743, 'SiLU': 54, 'Conv2d': 51, 'GroupNorm': 46, 'LayerNorm': 210, 'attention': 140, 'GEGLU': 70}
module calls per UNet forward (matrix + norm + activation ops): 1314
```

## What this shows
- A dispatch put in its own command buffer costs about 264 to 338 microseconds on this Mac (the "command-buffer floor" the earlier slices kept hitting); the same dispatches in one command buffer and one compute encoder cost about 3.5 microseconds for a trivial kernel and about 20 microseconds when each moves 12 MB (1,048,576 threads reading two and writing one float). Batching removes the floor: 76 times cheaper per dispatch for the tiny kernel, 17 times for the larger.
- One encoder per dispatch inside a single command buffer costs somewhat more (6.8 and 22.6 microseconds), so a UNet call can use one encoder.
- One SDXL UNet forward makes 1,314 counted module calls (743 Linear, 210 LayerNorm, 140 attention, 70 GEGLU, 54 SiLU, 51 Conv2d, 46 GroupNorm): at least that many dispatches, plus residual adds, skip concatenations, upsamples and the time embedding that this count omits. Arithmetic, not a measurement of a UNet: 1,314 x 3.5 us = about 4.6 ms of dispatch overhead per call batched, against 1,314 x 264 us = about 347 ms unbatched; over a 50-call render, about 0.23 s against about 17 s. The kernels' own run time is on top of either.

## What it does not show
- A trivial kernel and a streaming kernel only, serial dispatch type (each dispatch waits for the previous): no UNet, no dependent data flow between real kernels, no argument tables, no resource heap management for the activations a UNet call needs. The 3.5 microseconds includes the Kotoba host's encoding cost but no CPU-GPU overlap (the host waits for the whole buffer).
- The module-call count is a lower bound on dispatches and depends on how the kernels are fused (GroupNorm+SiLU, bias/residual epilogues and QKV projections can merge, which lowers it).
- One chip (M1 Max) at load 17 to 20; the 16 GiB M4 minis are untested. Not a CI step (needs a GPU host).
