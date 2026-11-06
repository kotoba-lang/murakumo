# The linear kernel: structure variants, and an explicit four-step unroll (stage M slice 33, JUN-927)

Recorded 2026-11-06T21:45:55+0900 on 21mbp.local, M1 Max, load average 15.28 14.50 20.41. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`. GPU timestamps (slice 23), 512 px, all 722 `linear_t` dispatches of one UNet call, binaries alternated.

## Variants tried this time (pass 1, ms; baseline = the [K, N]-weight kernel of slice 24)
| variant | GPU ms |
|---|---|
| baseline: 64 x 64 tile, 4 simdgroups, k step 8 | 289 to 331 (median ~318) |
| 128 x 64 tile, 8 simdgroups (256 threads), loads straight from device | 324 to 415 (not better) |
| 128 x 64 tile, 8 simdgroups, A and B tiles staged in threadgroup memory (K block 64, half4 loads) | 324 to 344 (not better) |
| k unrolled x2 with every fragment in its own named register | 291 to 323 (same) |
| **k unrolled x4 with every fragment in its own named register** | **265 to 293 (median ~278)** |
So the structural ideas I had listed as untried (a bigger tile, threadgroup double-buffering) do not pay for the linear layers: the 8-simdgroup 128-row tile reuses A better but gives the gain back elsewhere, and the staged version (the weights are already [K, N], so its loads are contiguous) is no faster than letting the simdgroup loads hit the cache. What worked in slice 24 as "unrolling is slower" was an artefact of writing the unrolled fragments as arrays indexed in a loop (the compiler spilled them: 1.7 times slower); with explicit named variables the x4 unroll is **about 12 percent faster**. It is applied (with the old step-8 loop as the tail for K not a multiple of 32) in the six programs that use the kernel.

## Gates (unchanged numerics: same products, same accumulation order within each 8-wide step)
- SDXL-topology UNet at model_channels 64 (256 and 512 px; worst stage 8.1e-3 / 9.0e-3) with its seven mutants; mini UNet (final 1.38e-3; 5.2 ms against 6.5 ms); VAE decoder at 64 / 512 / 1024 px (5.9e-3, 6.1e-3, 7.1e-3) with mutants; the whole text-to-image render in both checkpoint layouts (conditioning 1.0e-2 / 5.9e-3, image 1.6e-2 / 1.7e-2).
- Real width (model_channels 320, 2.57 B parameters) at 512 px against ComfyUI's `UNetModel`: worst stage 1.11e-2, final 8.4e-4, 546 ms for the graph:
```
MC=320 latent 64x64 (512 px, 2567463684 parameters, 2074 dispatches): emb=5.16e-06 h0=9.70e-04 h1=1.13e-03 h2=1.43e-03 h3=8.12e-04 h4=2.50e-03 h5=3.72e-03 h6=1.87e-03 h7=4.77e-03 h8=7.69e-03 m=1.11e-02 o0=8.48e-03 o1=7.39e-03 o2=3.95e-03 o3=4.49e-03 o4=3.78e-03 o5=2.07e-03 o6=1.69e-03 o7=1.46e-03 o8=1.21e-03 final=8.37e-04  finite=1 ok best=546.1 ms
```

## Wall clock per UNet call (synthetic weights; load average 15.28 14.50 20.41, so noisy)
```
latent 64x64 (512 px): 2100 kernels, best 0.43 s per UNet call; output ok n=16384 mean|x|=0.073 max|x|=0.339
latent 96x96 (768 px): 2100 kernels, best 0.84 s per UNet call; output ok n=36864 mean|x|=0.072 max|x|=0.353
latent 128x128 (1024 px): 2100 kernels, best 2.54 s per UNet call; output ok n=65536 mean|x|=0.073 max|x|=0.337
finite outputs
```
About 42 s of UNet time for 50 calls at 768 px at this moment (it was 84 s in slice 11, 52 s in slice 26). The 1024 px figure of the run above is inflated by the host's load (2.2 to 2.5 s across recent runs).

## What it does not show
Random / synthetic weights; no same-chip ComfyUI comparison; a busy host. Not tried: a register- or threadgroup-resident Q for the VAE's 512-wide attention (32 queries x 512 x 2 bytes would fill the threadgroup memory), and double-buffering the convolution's gathered tile.
