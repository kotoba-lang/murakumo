# Kotoba kernels against ComfyUI's own PyTorch code (I2 oracle, JUN-927)

Recorded 2026-11-06T16:06:53+0900 on 21mbp.local, Apple M1 Max. A one-off local measurement (owner decision 2026-11-06: compare against ComfyUI's Python); it is not a gate, a route or a fallback.

## What was fetched (owner-approved 2026-11-06)
- ComfyUI v0.44.0 source tarball, `https://github.com/comfyanonymous/ComfyUI/archive/refs/tags/v0.44.0.tar.gz`, 12,864,357 bytes, sha256 `7d796d74f42fe862eb55d1c217349fee92c2cac5d4ababe06c8b73f90c936dc0`, tag commit `2c75dc1b379a59e0880619d029607af5169a62ef`. Source only, no model weights. Extracted under `workspaces/claude/jun-927-comfy-oracle/` (outside any repository).
- `einops` 0.8.2 (pip, into a venv with system site-packages). torch 2.6.0 and numpy 1.26.4 were already installed for Homebrew Python 3.11.13; nothing else was fetched.
- ComfyUI's imports need two optional accelerator packages, `comfy_kitchen` and `comfy_aimdo`; neither was installed. `bench/oracle/comfyui/stubs/` has minimal stand-ins that report every accelerated path unavailable (plain PyTorch attention is used) and raise if called. They touch no math the comparison runs. ComfyUI is GPL-3.0; it is run, not copied into this repository.

## Method
1. A twin (`bench/kernels/{resblock,spatial}/kernel.c`) builds the synthetic inputs and parameters and writes them with its own output to a raw float32 file (`DUMP_OUT`).
2. `bench/oracle/comfyui/compare.py` loads those parameters into ComfyUI's `ResBlock` / `SpatialTransformer` (depth 1, linear proj_in/out, `comfy.ops.disable_weight_init`), runs it in float32 on one CPU thread, and compares with the twin's output.
3. The Kotoba program equals the twin by checksum on the same shapes (gate.sh, below), so the figures bound Kotoba against ComfyUI through the twin.
4. ComfyUI fixes 32 GroupNorm groups and uses eps 1e-6 for the transformer's GroupNorm, so the shapes use G=32 and `GNEPS=0.000001` (a new twin/Kotoba parameter; the default 1e-5 and the earlier checksums are unchanged). Feed-forward inner width is 4*C.

## Kotoba == twin at these shapes
```
reps=1 checksum equal: 49089543510894
reps=2 checksum equal: 221095464478883
mutant differs: 71693202150031 != 49089543510894
oracle: elements 16384 max_abs_err 1.183e-05 max_abs_ref 1.425e+01
abs err <= 1e-4: ok
gate passed (CI=32 CO=64 H=16 W=16 G=32 TE=64 REPS=2)
reps=1 checksum equal: 17928827753188
reps=2 checksum equal: 168295699011759
mutant differs: 246780040206391 != 17928827753188
oracle: elements 2048 max_abs_err 4.382e-06 max_abs_ref 1.127e+01
abs err <= 1e-4: ok
gate passed (SC=32 SH=8 SW=8 SG=32 SNH=2 SLC=16 SCC=24 SFI=128 GNEPS=0.000001 REPS=2)
```

## twin vs ComfyUI
```
PYTHONPATH=bench/oracle/comfyui/stubs python3.11 compare.py --comfy-src ComfyUI-0.44.0 --kind resblock --dump rb.bin --shape CI=32 CO=64 H=16 W=16 G=32 TE=64
{"kind": "resblock", "shape": {"CI": 32, "CO": 64, "H": 16, "W": 16, "G": 32, "TE": 64}, "elements": 16384, "max_abs_err_vs_twin": 7.62939453125e-06, "mean_abs_err_vs_twin": 1.1571644336072495e-06, "max_abs_ref": 14.252397537231445, "tol": 5e-05, "within_tol": true, "nudged_weight": "in_layers.2.weight", "nudged_max_abs_err": 0.0003986358642578125, "nudge_detected": true, "torch": "2.6.0", "numpy": "1.26.4", "threads": 1}
PYTHONPATH=bench/oracle/comfyui/stubs python3.11 compare.py --comfy-src ComfyUI-0.44.0 --kind spatial --dump sp.bin --shape SC=32 SH=8 SW=8 SG=32 SNH=2 SLC=16 SCC=24 SFI=128
{"kind": "spatial", "shape": {"SC": 32, "SH": 8, "SW": 8, "SG": 32, "SNH": 2, "SLC": 16, "SCC": 24, "SFI": 128}, "elements": 2048, "max_abs_err_vs_twin": 3.5762786865234375e-06, "mean_abs_err_vs_twin": 8.131937647704035e-07, "max_abs_ref": 11.265291213989258, "tol": 5e-05, "within_tol": true, "nudged_weight": "proj_in.weight", "nudged_max_abs_err": 0.006100177764892578, "nudge_detected": true, "torch": "2.6.0", "numpy": "1.26.4", "threads": 1}
```

## What this shows
- ComfyUI's own `ResBlock` and `SpatialTransformer` (PyTorch float32, CPU, one thread) agree with the f32 twin, and hence with the Kotoba program, to 7.6e-6 and 3.6e-6 at output magnitudes of 14.3 and 11.3 (mean 1.2e-6 and 8.1e-7), under a 5e-5 tolerance.
- The comparison can fail: nudging one weight element by 1e-2 moves the ResBlock output by 4.0e-4 and the transformer output by 6.1e-3, both above the tolerance.
- So the layer wiring (GroupNorm+SiLU order, time-embedding broadcast, skip conv, GEGLU gate half, cross-attention source, residuals, head split) matches ComfyUI's for these shapes, not only the twin's own reading of it.

## What it does not show
- One shape each, synthetic weights, float32 only (no f16, no bf16), one transformer layer, 32 groups, CPU. Not a real SDXL checkpoint and not a render.
- ComfyUI's attention here is the plain PyTorch path; its optimized attention variants were not exercised.
- The tolerance is mine (5e-5); it bounds float32 reorder differences, not a perceptual claim.
