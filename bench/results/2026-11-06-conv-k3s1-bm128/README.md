# A 128-channel tile for the 3x3 convolution: about 40 percent less convolution time (stage M slice 30, JUN-927)

Recorded 2026-11-06T21:12:45+0900 on 21mbp.local, M1 Max, **load average 14.23 22.37 27.10 (very busy: other processes were using the machine heavily during these runs)**. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`. Timer: per-kernel GPU timestamps (slice 23), kernels compared by alternating binaries.

## What I looked at, and what it showed
I went for GroupNorm (about 2 percent of a UNet call) and measured the VAE decode first, to see where its time goes at 1024 px (GPU time, one decode): `conv_k3s1` 6.9 s of ~9.2 s (75 percent), the 512-wide attention 1.2 s, **GroupNorm only 0.8 s (9 percent)**. The convolution was the lever, in the UNet as well.
The convolution's expensive part is the gather of the input tile (an address and a validity test per element), which cost more instructions than the matrix multiplies. The first specialised kernel (slice 23) computed 64 output channels per gathered tile.

## Variants (GPU time of all `conv_k3s1` dispatches; UNet at 512 px, VAE decode at 1024 px)
| variant | UNet 512 px | VAE 1024 px |
|---|---|---|
| 64 channels x 64 pixels, 4 simdgroups (slice 23) | 243 to 305 ms | 6.1 to 6.8 s |
| 128 channels x 64 pixels, 4 simdgroups of 64 x 32 | 1,144 to 1,370 ms | 31 to 33 s |
| **128 channels x 64 pixels, 8 simdgroups (256 threads) of 32 x 32** | **155 to 209 ms** | **3.8 to 4.0 s** |
The four-simdgroup 128-channel tile is 4 to 5 times SLOWER (accumulators for 64 x 32 per simdgroup do not fit in registers); the same tile with eight simdgroups of 32 x 32, which keeps the accumulator size of the old kernel and doubles the threads sharing one gathered tile, is about **37 to 43 percent faster** in both programs.

## Gates (numerics: the same MMA order, differences in the third digit of early stages; all inside the limits fixed in slice 10)
- SDXL-topology UNet at model_channels 64, 256 and 512 px: 21 stages, worst 8.1e-3 / 9.0e-3, final unchanged at the digits shown (1.2e-3 class); seven mutants detected (including the one that breaks the convolution kernel's validity mask).
- Real width (model_channels 320, 2.57 B parameters) at 512 px, all 21 stages against ComfyUI's `UNetModel`: worst 1.11e-2, final 8.4e-4, 584 ms for the graph on this very busy host:
```
MC=320 latent 64x64 (512 px, 2567463684 parameters, 2074 dispatches): emb=5.16e-06 h0=9.70e-04 h1=1.13e-03 h2=1.43e-03 h3=8.12e-04 h4=2.50e-03 h5=3.72e-03 h6=1.87e-03 h7=4.77e-03 h8=7.69e-03 m=1.11e-02 o0=8.48e-03 o1=7.39e-03 o2=3.95e-03 o3=4.49e-03 o4=3.78e-03 o5=2.07e-03 o6=1.69e-03 o7=1.46e-03 o8=1.21e-03 final=8.37e-04  finite=1 ok best=583.9 ms
```
- VAE decoder (normal scale; and the float16-range stress of slice 29), mini UNet (final 1.38e-3; 6.5 ms against 5.7 ms before: on a 16 x 16 latent the larger tile wastes work), and the whole text-to-image render in both the Hugging Face and the float16 single-file layouts (ids exact, conditioning 1.0e-2 / 5.9e-3, image 1.6e-2 / 1.7e-2): all pass, mutants detected.
```
mini (3104323 parameters, 64 px): cin=5.881e-04 mid=1.856e-03 l3=2.748e-03 l2=3.797e-03 l1=6.018e-03 b0=1.091e-02 final=4.847e-03 ok best=0.04 s
sdxl64 (49490179 parameters, 512 px): final=6.208e-03 ok best=0.85 s
sdxl128 (49490179 parameters, 1024 px): final=6.255e-03 ok best=6.02 s
mutant [attn-scale]: final error 1.892 (detected)
mutant [upsample]: final error 4.403 (detected)
mutant [residual]: final error 3.05 (detected)
mutant [silu]: final error 3.029 (detected)
gate passed
```

## Speed (host load 18 to 28, so absolute figures are inflated; wall clock, slice-11 timing program, synthetic weights)
```
latent 64x64 (512 px): 2100 kernels, best 0.46 s per UNet call; output ok n=16384 mean|x|=0.073 max|x|=0.339
latent 96x96 (768 px): 2100 kernels, best 1.03 s per UNet call; output ok n=36864 mean|x|=0.072 max|x|=0.353
latent 128x128 (1024 px): 2100 kernels, best 2.55 s per UNet call; output ok n=65536 mean|x|=0.073 max|x|=0.337
finite outputs
```
- The VAE decode at 512 px dropped from about 1.4 to 1.9 s to 0.85 s and at 1024 px from 8.1 s to 6.0 s (both on busy hosts).
- A profile of one 1024 px UNet call, GPU time under this load:
```
latent 128x128 (1024 px)
  kernel        count     pass1 ms     pass2 ms
  linear_t        722       1187.6       1215.7
  conv_k3s1        38        503.1        530.5
  attn_hc         140        346.2        360.0
  conv             13         94.5         90.3
  gn_silu_h        35         48.9         53.8
  geglu_h          70         23.7         23.7
  ln_h            210         23.4         20.6
```
(at a quieter moment these were linear 714, conv 636 and attention 235 ms; the convolution is now at 503 ms even though the linear layers are inflated by 66 percent by the load, so the real convolution gain is larger than it looks here.)

## What it does not show
Synthetic or random weights; no same-chip ComfyUI comparison; absolute times from a heavily loaded host (the comparisons that matter are the alternated GPU-timer runs above). GroupNorm itself was not tuned: it is 2 percent of a UNet call and 9 percent of the VAE decode, and the convolution was the better lever.
