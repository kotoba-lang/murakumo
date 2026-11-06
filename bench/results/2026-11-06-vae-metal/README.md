# SDXL's VAE decoder on the Apple GPU, gated against ComfyUI's own Decoder (stage M slice 16, JUN-927)

Recorded 2026-11-06T18:41:15+0900 on 21mbp.local, M1 Max, load average 10.59 12.11 12.33 (other agents' processes running). amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, profile `amu-program-metal`. Command: `sh bench/kernels/vae-metal/run.sh <amu> <ComfyUI-0.44.0> <stubs> <python with torch, safetensors, einops, numpy>`.

## What it does
One Kotoba program decodes a latent to an image the way ComfyUI's `Decoder` does (post_quant_conv, conv_in, mid.block_1, mid.attn_1, mid.block_2, four up levels of three residual blocks with a nearest 2x Upsample + conv between them, norm_out, SiLU, conv_out), with the existing GroupNorm+SiLU and implicit-GEMM convolution kernels plus one new kernel: single-head attention over all pixels with head dimension equal to the mid width (512 in SDXL; the earlier attention kernel has 64). 125 dispatches on the small config and 169 at 1024 px, in command buffers of 2 dispatches that are each waited for and status-checked. **Two things this slice taught about the GPU watchdog** (`kIOGPUCommandBufferCallbackErrorImpactingInteractivity`, which kills command buffers that hold the GPU too long on a busy host): (1) a single 1024 px decode contains dispatches of 0.4 to 1.9 s (the 512-wide attention over 16,384 pixels, 600 GMAC convolutions), so the convolution is now issued in pieces of about 60 GMAC through a pixel-tile offset, and the attention in pieces of 2,048 queries; (2) my first version waited for and checked only the LAST command buffer of a decode, so an earlier killed buffer let the program continue with garbage: the same binary gave a correct image in three runs and a wrong one (error 2.7) in the fourth, and a recorded run of this gate failed that way. Every command buffer is now checked: six consecutive 1024 px runs exited cleanly with the same 6.255e-03 error and no watchdog message. Four float and two half scratch buffers are reused (the 1024 px decode needs about 7 GB).

## Result (limits fixed in slice 10, before any VAE run: final 1e-2, each stage 2e-2; |gpu - ref| / max(1, |ref|))
```
mini (3104323 parameters, 64 px): cin=5.881e-04 mid=1.856e-03 l3=2.748e-03 l2=3.797e-03 l1=6.018e-03 b0=1.091e-02 final=4.847e-03 ok best=0.06 s
sdxl64 (49490179 parameters, 512 px): final=6.208e-03 ok best=1.91 s
sdxl128 (49490179 parameters, 1024 px): final=6.255e-03 ok best=9.91 s
mutant [attn-scale]: final error 1.892 (detected)
mutant [upsample]: final error 4.403 (detected)
mutant [residual]: final error 3.05 (detected)
mutant [silu]: final error 3.029 (detected)
gate passed
```
- The oracle is ComfyUI v0.44.0's own `Decoder` class in float32 on the CPU, with random weights (std 0.7 / sqrt(fan-in), GroupNorm gammas near 1) and a random latent. The 512 px and 1024 px runs are SDXL's real decoder shape (ch 128, mult 1 2 4 4, 49.5 M parameters; the encoder's other 34 M are not needed).
- Timing is the best of 3 whole decodes on a host with a load average of 50.69 28.86 20.18 (other agents' processes): the figures in the block above (about 9.7 s at 1024 px in the six clean runs, against 7.4 to 7.9 s for the earlier unsplit version, whose runs could be silently wrong: the per-buffer waits and smaller pieces cost about 25 percent). One decode per image, so about 5 percent of one 25-step guided 1024 px render at the UNet speed of the slice-11/12 record (about 4.1 s per call, 50 calls); not worth tuning before the UNet.

## What it does not show
- Random weights: no trained VAE was read; the real `sdxl_vae.safetensors` may name or store tensors differently (the old `first_stage_model.decoder.*` / `.mid.attn_1.q` 1x1-conv layout is used here; some checkpoints store the attention projections as Linear `[C, C]`, which would need a reshape).
- No scaling of the latent by 0.13025 (done by the caller in ComfyUI), no clamp/rescale of the output to [0, 1], no encoder, no tiled decode (ComfyUI falls back to tiling when memory is short; this program needs the whole image's activations at once).
- No gradient of speed work: the convolution and GroupNorm kernels are the general ones; the single-head attention reloads Q per key block (no register-resident Q) and was not tuned; the LK must be a multiple of 32.
- Four mutants (attention scale 1, Upsample reading the wrong column, residual dropped in the identity block, SiLU dropped) are detected on the small config.
