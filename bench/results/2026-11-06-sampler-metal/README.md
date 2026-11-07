# The sampling loop on the Apple GPU, gated against ComfyUI's own (stage M slice 14, JUN-927)

Recorded 2026-11-06T18:21:38+0900 on 21mbp.local, M1 Max. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, profile `amu-program-metal`. Command: `sh bench/kernels/sampler-metal/run.sh <amu> <ComfyUI-0.44.0> <stubs> <python with torch, numpy, tqdm>`.

## What it contains
One Kotoba program (Metal route) that on the GPU: builds the 1,000-entry discrete sigma table of `ModelSamplingDiscrete` (scaled-linear betas 0.00085 to 0.012, cumulative product, sigma = sqrt((1 - abar) / abar)); picks the schedule the way ComfyUI's `simple` scheduler does (index `floor(i * 1000 / steps)` from the top, then a final 0); and runs the Euler loop: EPS input scaling x / sqrt(sigma^2 + 1), two stand-in "model" calls, classifier-free guidance on the denoised values (cfg 7.5), and the Euler update. Each step is separate dispatches, one command buffer each.

## Result (limits fixed before the first agreeing run: 1e-5 sigmas and schedule, 1e-4 final latent, relative to the largest reference value)
```
steps=20: sigma_table=9.788e-07 schedule=9.788e-07 final=7.633e-06
steps=30: sigma_table=9.788e-07 schedule=9.788e-07 final=5.763e-06
mutant [guidance]: final error 8.365e+02 (detected)
mutant [inputscale]: final error 9.994e-01 (detected)
mutant [schedule]: final error 9.942e+03 (detected)
mutant [sign]: final error 2.785e+04 (detected)
gate passed
```
The final latent is the result of about 4,000-fold cancellation (14 down to 0.003), so it is a sharp check of every step: a 7.0 instead of 7.5 guidance scale (a wrong float bit pattern I wrote while building this) gave a 1.9x wrong answer and was found by the first comparison.

## What it does not show
- The two "models" are toy elementwise functions, not the UNet: this gates the sampler arithmetic, the schedule and the sigma table, not image quality. Joining the UNet is wiring (the UNet call replaces the two toy kernels; two calls per step, or one batched), not done here.
- Only Euler, only the `simple` scheduler, only the EPS parameterisation, cfg applied on denoised values, no ancestral/DPM++ samplers, no `normal`/`karras` schedulers, no random initial noise or max-denoise noise scaling, N = 4,096 elements.
- Oracle: the real ModelSamplingDiscrete class (imported), and the source text of `simple_scheduler`, `sample_euler`, `to_d`, `append_dims` (taken from ComfyUI's files by ast and executed; importing the modules whole needs the entire model stack, `torchsde` and stubbed `comfy_kitchen`). `torchsde` is stubbed as an empty module (sample_euler does not call it). CPU float32 torch 2.6.0.
- Timing was not measured (a handful of tiny dispatches per step; the cost is the UNet).
