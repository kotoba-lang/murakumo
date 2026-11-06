# Attention: single-pass online softmax, and the real-width UNet gate at 512 px (stage M slice 26, JUN-927)

Recorded 2026-11-06T20:15:51+0900 on 21mbp.local, M1 Max, load average 7.12 7.84 7.92 (it rose from 8 to 11 during these runs; every figure below is a GPU time or a wall time on a shared host). amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`. Timer: the per-kernel GPU timestamps of slice 23.

## 1. Real width at 512 px
`MUTANTS=0 sh bench/kernels/unet-sdxl-named/run.sh <amu> <ComfyUI> <stubs> <python> "320:64"` (model_channels 320, 2,567,463,684 parameters, a 64 x 64 latent = 512 px), run with the attention below:
```
MC=320 latent 64x64 (512 px, 2567463684 parameters, 2074 dispatches): emb=5.16e-06 h0=9.70e-04 h1=1.13e-03 h2=1.43e-03 h3=8.12e-04 h4=2.50e-03 h5=3.72e-03 h6=1.87e-03 h7=4.77e-03 h8=7.69e-03 m=1.11e-02 o0=8.48e-03 o1=7.39e-03 o2=3.95e-03 o3=4.49e-03 o4=3.78e-03 o5=2.07e-03 o6=1.69e-03 o7=1.46e-03 o8=1.21e-03 final=8.37e-04  finite=1 ok best=660.0 ms
```
All 21 stages agree with ComfyUI's own `UNetModel` (worst 1.11e-2 in the middle block, final 8.4e-4; limits 2e-2 / 1e-2), 660 ms for the whole graph with the weights converted from the 10.27 GB float32 file in the same command buffer. The first 512 px run of this gate (with the two-pass attention) gave the same picture: worst 1.08e-2, final 8.0e-4, 663 ms.

## 2. What the attention costs, and what was tried (attn_hc GPU time per call at 1024 px, 140 dispatches, alternating runs)
| variant | pass 1 | pass 2 |
|---|---|---|
| two-pass (statistics pass, then scores recomputed for P and P V), softmax statistics by 32 threads | 470 to 526 | 470 to 489 |
| K tile loaded untransposed (K stored [dim, key]) | 453 to 483 | 450 to 459 |
| softmax statistics by 4 threads per row with shuffles (all 128 threads) | 350 to 398 | 348 to 363 |
| **single pass, online softmax, diagonal-matrix rescale of the running output, no threadgroup barrier in the key loop** | **346 to 361** (host busier: baseline 666 to 767 in the same alternation) | **259 to 304** (baseline 552 to 629) |

- The old kernel kept one simdgroup of 32 threads doing the row statistics serially while three idled at a barrier, and computed the 32 x 32 scores twice. Spreading the statistics over all threads gives about 25 percent; going to one pass with the running maximum and sum (the rescale of the 8 x 64 output accumulator is a multiplication by an 8 x 8 diagonal matrix of exp(m_old - m_new), each simdgroup working on its own 8 query rows so no threadgroup barrier is needed) gives about **45 to 50 percent less attention GPU time** (472 to about 235 ms at 1024 px in the last profile, with the host busier). The transposed K load, which cost 18 percent in the linear layers, costs only ~4 percent here and is not worth a producer change.
- Numerics: the results differ from the old kernel in the last digits (P is kept unnormalised in half and divided by the running sum at the end) and agree with ComfyUI as well: the SDXL-topology UNet at model_channels 64 (256 and 512 px; worst stage 8.1e-3 and 8.9e-3, final 1.2e-3), the mini UNet (final 1.38e-3, worst stage 4.0e-3), and the whole text-to-image render (ids exact, conditioning 1.0e-2, steps 2.9e-2, image 1.6e-2; the CLIP towers use the same kernel with the causal mask; seven mutants detected). A new mutant that removes the final normalisation by the running sum is detected (final error 1.18); one that removes only the per-block rescale moves the final output by 2e-2 (above the 1e-2 limit, below the 5e-2 mutant bar), so the weaker one is not used.

## 3. Wall clock per UNet call (slice-11 timing program, synthetic weights, load average 11)
**0.54 s at 512 px, 1.04 s at 768 px, 2.19 s at 1024 px** (slice 24: 0.56 / 1.12 / 2.32; slice 12: 0.84 / 2.27 / 4.49). At 768 px the 50 calls of a 25-step guided render are about **52 s** of UNet time; the text encoders take about 0.1 s and the VAE decode at 1024 px about 6 s.

## What it does not show
- Synthetic weights in the timing program; random weights in the gates; no trained model, and ComfyUI was not run on this Mac for a same-chip comparison.
- GroupNorm+SiLU (about 2 percent of a call at 1024 px) and the transposed-K idea were left alone. The attention kernel is still head-dimension 64 only (the VAE's 512-wide single-head attention is a different kernel and was not touched).
