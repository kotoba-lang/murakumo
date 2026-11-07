# Single-file loading: mixed dtypes and the tensors a real checkpoint also holds (stage M slice 28, JUN-927)

Follow-up to slice 27 (`bench/results/2026-11-06-single-file-f16/README.md`), same command (`LAYOUT=single sh bench/kernels/pipeline-metal/run.sh ...`). Recorded on the same machine and amu checkout.

## What the harder fixture adds
The slice-27 fixture was uniformly float16 with only the tensors the loader asks for, which flattered it. A real checkpoint is neither. The fixture now also contains, in the float16 file: **the VAE encoder tensors and `quant_conv`** (indexed, never read), **scalar tensors of shape [] (`model_ema.decay`, `model_ema.num_updates`)**, **`alphas_cumprod` as float32**, and **four tensors the loader does read stored as float32** (the UNet output norm `out.0.weight` / `.bias` and bigG's `ln_final.weight` / `.bias`).

## What it found
The first run of the harder fixture produced NaN everywhere. Cause: the loader decided between float32 and float16 mode from the first tensor in the header, and safetensors orders the header by dtype, so the first entry was the float32 `alphas_cumprod`: the whole file was read as float32. Two fixes: the mode is now "any float16 tensor in the file", and a float32 tensor inside a float16 file that the kernels read as float is copied (not mis-read as half) into the parameter buffer. A real checkpoint that mixes dtypes would have hit the first bug on its first load.

## Result
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
mutant [f16-params-scaled]: worst error 9.303 (detected)
mutant [f32-in-f16-file]: worst error 6050 (detected)
gate passed
```
Same agreement as slice 27 (ids exact, conditioning 5.9e-3, steps 2.5e-2 to 2.7e-2, image 1.7e-2), 4,275 dispatches, eleven mutants detected, the new one being a float32 tensor inside the float16 file read as half (error 6050). The float32 / Hugging Face layout still passes unchanged.

## What it does not show
- Still no real checkpoint, no real-size float16 file, and no trained weights (D1). BF16 is refused, not supported. Other dtypes (F8, I64 token ids buffers as in some single files, `position_ids` stored as int64) are indexed by size and never read; an int64 `position_ids` tensor is not in this fixture.
