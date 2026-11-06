# A whole SDXL render in one GPU program, gated against ComfyUI's code (stage M slice 18, JUN-927)

Recorded 2026-11-06T19:11:21+0900 on 21mbp.local, M1 Max, load average 16.18 27.43 31.71. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, profile `amu-program-metal`. Command: `sh bench/kernels/pipeline-metal/run.sh <amu> <ComfyUI-0.44.0> <stubs> <python with torch, safetensors, einops, numpy, tqdm>`.

## What it joins
The pieces of slices 14, 16 and 17, in one Kotoba program (about 74 KB; the import route is still open, so it is assembled from those programs' text): from one safetensors file holding an SDXL-topology UNet (model_channels 64, 158 M parameters) and a VAE decoder (ch 32) in ComfyUI's names, and from a noise latent and two (77 x 2,048 context, 2,816 label) pairs, on the GPU it builds the sigma table and the `simple` schedule, then for each of 4 Euler steps: scales the latent by 1 / sqrt(sigma^2 + 1), finds the nearest timestep in log-sigma, runs the **same recorded 2,074-dispatch UNet list twice** (conditional, then unconditional, after copying the matching context and label into the buffers the list is bound to), combines the denoised values with classifier-free guidance (scale 4) and takes the Euler step; after the last step the latent goes through post_quant_conv and the decoder to a 256 x 256 image. 2,204 dispatches are recorded in all; the host runs them in segments.

The oracle (make_fixture.py) is the same render in ComfyUI v0.44.0's own `UNetModel`, `Decoder`, `ModelSamplingDiscrete` (including its `timestep` search), `simple_scheduler` and `sample_euler` (the last two extracted from the files by ast), float32 on the CPU, with random weights.

## Result
```
4 Euler steps (2 UNet passes each) + VAE decode, 256 px, 2204 recorded dispatches: step0=2.672e-02 step1=2.786e-02 step2=2.808e-02 step3=2.788e-02 image=2.005e-02  finite=1  ref|x_final|max=61.23 ref|image|max=3.37 ok process wall 1 s
mutant [guidance]: worst error 6.283 (detected)
mutant [timestep-reversed]: worst error 4.3 (detected)
mutant [uncond-context]: worst error 1.857 (detected)
mutant [vae-residual]: worst error 9.244 (detected)
gate passed
```
- The standalone UNet gate of slice 17 is accurate to ~1e-3. Here the latent differs from ComfyUI's by 2.7e-2 to 2.8e-2 after every step and the image by 2.0e-2 (|gpu - ref| / max(1, |ref|), worst element of 4,096 / 196,608), with the reference latent reaching 61 and the image 3.4. **The 5e-2 limit was set after seeing these numbers** (the other gates here fix limits first): a guided step multiplies an eps error by cfg + (cfg - 1) = 7 and by the step length (sigma_0 - sigma_1 = 10.5), so f16 UNet errors of 1e-3 cannot stay under 1e-2 after one step; the error does not grow over the later steps (the step lengths shrink).
- Process wall time for the whole run (4 steps, 8 UNet passes, the decode, the load of 0.8 GB of weights) is about 1 to 2 s on this Mac under load; it is not a benchmark, and the model is the small one.
- Four mutants (guidance dropped, the timestep reversed, the unconditional pass given the conditional context, the VAE residual dropped) are detected. A fifth idea, searching the nearest timestep in linear instead of log sigma, gave the same output on this schedule and is not a useful mutant.

## What it does not show
- Tiny widths (model_channels 64, VAE ch 32), random weights, 256 px: the numbers say the pieces compose and the control flow (two passes, buffer reuse across passes, the schedule, the timestep, the decode) is right; they say nothing about image quality or SDXL-width accuracy.
- The contexts and label vectors are inputs read from files: no CLIP tower in the loop (slice 15 gates the towers separately), no L+G concatenation, no size/crop conditioning embeddings, no tokenizer in front.
- Euler only, the `simple` schedule only, no latent scale factor (0.13025) and no clamping of the image.
- One Mac, one resolution. No trained checkpoint (D1) and no decision on where a real render runs (D4).
