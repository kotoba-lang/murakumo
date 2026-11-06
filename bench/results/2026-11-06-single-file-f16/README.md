# Loading an SDXL single-file checkpoint: float16 weights, real key names, OpenCLIP tower (stage M slice 27, JUN-927)

Recorded 2026-11-06T20:27:45+0900 on 21mbp.local, M1 Max, load average 6.46 12.33 12.10. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, profile `amu-program-metal`. Command: `LAYOUT=single sh bench/kernels/pipeline-metal/run.sh <amu> <ComfyUI-0.44.0> <stubs> <python with torch, transformers, safetensors, einops, numpy, tqdm> <sd1_tokenizer dir>`.

## Why
Every earlier slice read float32 files in Hugging Face / ComfyUI-module names that I wrote myself. A real SDXL checkpoint (`sd_xl_base_1.0.safetensors`) is **one float16 file** with a different naming for three of its four parts, so the loader is the last piece of code between the pipeline and a real checkpoint. It can now be gated before a checkpoint is chosen, because the layout is fully specified by ComfyUI's own conversion code.

## What the render program now reads
- **dtype:** F16 or F32 per file (BF16 is refused with a message). Weights are converted to half on the GPU as before (`cph`, `cph_t` for F16); the tensors that kernels read as float (biases, norm parameters, embedding tables, `time_embed` / `label_emb` / `emb_layers` weights) are converted to float32 once, on the GPU, into a separate parameter buffer at their first use (`h2f`; the offsets are memoised per tensor). For an F32 file nothing changes.
- **Names:** a name that is not in the file as written is also tried under `model.diffusion_model.` (UNet), `first_stage_model.decoder.` and `first_stage_model.` (VAE). The CLIP-L tower is read as Hugging Face names under `conditioner.embedders.0.transformer.`; the bigG tower as **OpenCLIP names** under `conditioner.embedders.1.model.`: `transformer.resblocks.N.ln_1 / ln_2 / attn.in_proj_weight (q, k, v stacked, split by element offset) / attn.in_proj_bias / attn.out_proj / mlp.c_fc / mlp.c_proj`, `ln_final`, `positional_embedding`, `token_embedding.weight`, and `text_projection` stored [in, out] (so it needs no transposition for the linear kernel). Scalar tensors such as `logit_scale` (shape []) are indexed and ignored.
- **The fixture** (`make_fixture.py ... single`) writes exactly that layout from the same random models: float16 file, the UNet under `model.diffusion_model.`, the VAE decoder under `first_stage_model.decoder.` and `post_quant_conv` under `first_stage_model.`, CLIP-L under its prefix, bigG converted to OpenCLIP names by inverting `comfy/diffusers_convert.py`'s `textenc_conversion_lst` and its q/k/v fusion order, `text_projection` transposed, a scalar `logit_scale` added. All weights are rounded to float16 first, so the oracle (Hugging Face towers and ComfyUI's UNet / VAE / sampler, float32 CPU) holds the identical values.

## Result (same limits as slices 18 to 22: conditioning 5e-2, steps and image 5e-2, token ids exact)
```
4 Euler steps (2 UNet passes each) + VAE decode, 256 px, 4275 recorded dispatches: ids_mismatch=0.000e+00 ctx_c=5.851e-03 y_c=1.239e-03 ctx_u=5.851e-03 y_u=1.436e-03 step0=2.543e-02 step1=2.603e-02 step2=2.654e-02 step3=2.705e-02 image=1.720e-02  finite=1  ref|x_final|max=62.44 ref|image|max=4.13 ok process wall 2 s
mutant [guidance]: worst error 4.925 (detected)
mutant [timestep-reversed]: worst error 2.552 (detected)
mutant [uncond-context]: worst error 4.713 (detected)
mutant [concat-order]: worst error 11.46 (detected)
mutant [size-order]: worst error 2.14 (detected)
mutant [g-padding]: worst error 2.241 (detected)
mutant [vae-residual]: worst error 8.878 (detected)
mutant [inproj-v-offset]: worst error 11.88 (detected)
mutant [text-projection-transposed]: worst error 4.187 (detected)
mutant [f16-params-scaled]: worst error 19.94 (detected)
gate passed
```
- The whole render, from prompt text to image, reads the float16 single-file layout and agrees with the oracle as closely as the float32 layout did (conditioning 5.9e-3 here against 1.0e-2 there, steps 2.5e-2 to 2.7e-2, image 1.7e-2; the float16 weights are exactly representable, so one source of rounding disappears). 4,275 dispatches are recorded (1,389 more than with float32 files: the weight and parameter conversions).
- Ten mutants are detected, three of them specific to this slice: the fused `in_proj` v slice taken from the q offset (11.9), `text_projection` transposed like a Linear weight (4.2), and the float16 to float32 parameter conversion scaled (19.9). The float32 layout still passes unchanged (conditioning 1.0e-2, steps 2.9e-2, image 1.6e-2).

## What it does not show
- **No real checkpoint was read.** The key names are those ComfyUI's code defines; a real file also contains tensors this loader never asks for (the VAE encoder, `model_ema` entries, EMA / `alphas_cumprod` buffers) and I could not test how they index beyond a scalar `logit_scale`. A real SDXL file is 6.9 GB; the memory arithmetic (file buffer 6.9 GB + halves 5.2 GB + parameter buffer under 0.8 GB) was not exercised here, only the random small-UNet layout at the same dtype, though the F32 real-width run (slice 25) used a 10 GB buffer without trouble.
- SDXL's fp16 VAE is known to overflow when run in float16; this program keeps activations in float32 between layers, so that failure mode is not expected, but it was not tested on trained weights.
- The VAE's attention projections are assumed to be 1x1 convolutions `[C, C, 1, 1]` as in the original SDXL VAE; some re-saved VAEs store them as `[C, C]` Linear weights (same bytes, different shape), which the loader would also read since it only checks element counts.
- Still D1 (which checkpoint, its licence, the download) and D4 (where it runs).
