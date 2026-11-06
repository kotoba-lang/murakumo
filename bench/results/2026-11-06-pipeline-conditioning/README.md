# SDXL's conditioning built on the GPU: L+G concatenation, pooled vector, size/crop embeddings (stage M slice 19, JUN-927)

Recorded 2026-11-06T19:16:06+0900 on 21mbp.local, M1 Max, load average 14.93 18.22 26.10. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`. Command: `sh bench/kernels/pipeline-metal/run.sh <amu> <ComfyUI-0.44.0> <stubs> <python with torch, safetensors, einops, numpy, tqdm>` (the slice-18 gate, extended).

## What changed
The slice-18 render program took its two contexts (77 x 2,048) and label vectors (2,816) from files. It now takes what SDXL's two text towers produce and builds the conditioning on the GPU, as ComfyUI does: the penultimate hidden states of CLIP-L (77 x 768) and CLIP-G (77 x 1,280) concatenated **L first, then G** into the 2,048-wide context (`SDXLClipModel.encode_token_weights`, rows 77 .. 127 zero), and the 2,816-wide label vector `adm` = the 1,280-wide pooled vector of the G tower followed by `Timestep(256)` embeddings of **height, width, crop_h, crop_w, target_height, target_width** in that order (`SDXL.encode_adm`; cosine half then sine half). The oracle computes both with ComfyUI's own code: `torch.cat`, the real `encode_adm` and `sdxl_pooled` source (extracted by ast from model_base.py) and the real `Timestep` class, with six distinct size values (256, 384, 16, 32, 512, 640) so an ordering mistake shows. The same fixture then drives the whole render of slice 18.

## Result
```
4 Euler steps (2 UNet passes each) + VAE decode, 256 px, 2208 recorded dispatches: ctx_c=0.000e+00 y_c=3.052e-05 ctx_u=0.000e+00 y_u=3.052e-05 step0=2.840e-02 step1=3.301e-02 step2=3.220e-02 step3=3.203e-02 image=1.728e-02  finite=1  ref|x_final|max=72.86 ref|image|max=3.54 ok process wall 1 s
mutant [guidance]: worst error 4.558 (detected)
mutant [timestep-reversed]: worst error 3.118 (detected)
mutant [uncond-context]: worst error 4.151 (detected)
mutant [concat-order]: worst error 6.36 (detected)
mutant [size-order]: worst error 3.393 (detected)
mutant [vae-residual]: worst error 14.51 (detected)
gate passed
```
- The concatenated contexts are bit-identical to ComfyUI's (error 0, they are copies); the label vectors agree to 3.1e-5. **The limit for them, 1e-4, replaced an initial 1e-5 that was too tight**: float32 sine and cosine of arguments up to 640 rad are good to ~3e-5 (slice 9 allowed 1e-3 for 1,000 rad for the same reason). The 5e-2 limit on the steps and the image is slice 18's (set after its first run).
- Six mutants are detected, two of them new: L and G concatenated in the wrong order, and the six size values in the wrong order.
- The render itself is unchanged in kind: 2,208 recorded dispatches, step latents within 3.3e-2 and the image within 1.7e-2 of ComfyUI's (random weights shift with the new conditioning; the earlier run had 2.8e-2 and 2.0e-2).

## What it does not show
- The tower outputs are still INPUTS read from files (random arrays standing in for the penultimate states and the pooled vector). The towers themselves are gated in slice 15 (CLIP-L and bigG at full size), and the tokenizer in an earlier slice, but they are separate programs: **running tokenizer, both towers and the render in one program is not done** (each tower program reads its own safetensors file and has its own recorded geometry; joining two of them in one process needs the tower recorder parametrised by weight prefix and size, which was not written).
- ComfyUI pads the G tower's token ids with 0 rather than the end token (`pad_with_end=False`, read in sdxl_clip.py, not run), and applies its token weighting and clip skip: none of that is exercised here.
- Size/crop values are fixed inputs of the run, not parsed from a request; no refiner conditioning (aesthetic score), no unCLIP.
