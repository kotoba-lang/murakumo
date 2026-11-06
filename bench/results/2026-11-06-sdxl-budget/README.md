# SDXL UNet parameter and MAC budget (I3 design input, JUN-927)

Recorded 2026-11-06T16:13:56+0900 on 21mbp.local. A one-off local measurement; not a gate or a route.

- Tool: `bench/oracle/comfyui/sdxl_budget.py`, ComfyUI v0.44.0 (tarball sha256 `7d796d74f42fe862eb55d1c217349fee92c2cac5d4ababe06c8b73f90c936dc0`, see [2026-11-06-comfyui-oracle](../2026-11-06-comfyui-oracle/README.md)), Homebrew Python 3.11.13, torch 2.6.0, stubs for the optional `comfy_kitchen` / `comfy_aimdo` imports.
- Config: SDXL as in ComfyUI `supported_models.SDXL` (`model_channels` 320, `transformer_depth` [0,0,2,2,10,10], output [0,0,0,2,2,2,10,10,10], middle 10, `context_dim` 2048, `adm_in_channels` 2816, linear projections, 64-wide heads), built on the `meta` device. No weights, no checkpoint, no render.
- Command: `cd ComfyUI-0.44.0 && PYTHONPATH=<stubs>:. python3.11 sdxl_budget.py`

```
unet params 2567463684
latent 64x64 (512px) per UNet call, batch 1: conv 203 G, linear 564 G, attention 28 G, total 794 GMAC
latent 96x96 (768px) per UNet call, batch 1: conv 457 G, linear 1236 G, attention 128 G, total 1821 GMAC
latent 128x128 (1024px) per UNet call, batch 1: conv 812 G, linear 2177 G, attention 392 G, total 3381 GMAC
```

(One run of the script, final version; the 768 px line is the one the ADR uses.)

## What it counts and does not
- Counts Conv2d and Linear (output elements x fan-in) and attention score and value products (2 x B x Lq x Lk x D). It does not count GroupNorm, SiLU, softmax, LayerNorm or elementwise work, the text encoders, or the VAE (a VAE decode count failed on ComfyUI's attention memory probe and was not retried).
- CLIP-L and CLIP-G text-tower parameter counts (123.06 M and 694.66 M) are arithmetic from ComfyUI's `sd1_clip_config.json` and `clip_config_bigG.json` dimensions, not counted from a model. The VAE's 83,653,863 parameters were counted on the meta device.
- The time table in the ADR divides 25 x 2 x 1,821 GMAC by rates measured for other operations; it is arithmetic, not a measurement of any render.
