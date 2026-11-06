# Software pipelining of the convolution's gather (stage M slice 34, JUN-927)

Recorded 2026-11-06T21:57:35+0900 on 21mbp.local, M1 Max, load average 9.17 13.91 17.62. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`. GPU timestamps (slice 23), binaries alternated.

## What changed
The 3x3 convolution's expensive part is the gather of the input tile. In the kernel of slice 30 each iteration gathered the tile for k and then multiplied; the gather's device loads were therefore always waited for before the matrix work started. Now the loaded values are held in registers: at the top of an iteration the tile gathered during the previous iteration is stored to threadgroup memory, and the next tile's gather loads are issued before the matrix multiplies of the current tile, so their latency hides behind them (16 half values per thread; the running (channel, tap) state simply continues). Applied to the six programs that carry the kernel.

## Result (conv_k3s1 GPU time)
| | before | after |
|---|---|---|
| UNet call, 512 px, 38 dispatches (pass 1) | 140 to 188 ms | 128 to 135 ms |
| VAE decode, 1024 px, 76 dispatches | 3.79 to 4.12 s | 3.60 to 3.82 s |
About 10 to 15 percent less in the UNet and about 7 percent in the VAE: the loads were only partly the bottleneck.

## Gates (unchanged numerics: same products and accumulation order)
SDXL-topology UNet at model_channels 64 (256 / 512 px, seven mutants, including the one that breaks this kernel's validity mask), mini UNet (final 1.38e-3, 4.8 ms), VAE decoder at 64 / 512 / 1024 px (5.9e-3, 6.1e-3, 7.1e-3), whole text-to-image render in both checkpoint layouts, and the real-width UNet (model_channels 320, 2.57 B parameters) at 512 px against ComfyUI's `UNetModel`: worst stage 1.11e-2, final 8.4e-4, 532 ms.
```
MC=320 latent 64x64 (512 px, 2567463684 parameters, 2074 dispatches): emb=5.16e-06 h0=9.70e-04 h1=1.13e-03 h2=1.43e-03 h3=8.12e-04 h4=2.50e-03 h5=3.72e-03 h6=1.87e-03 h7=4.77e-03 h8=7.69e-03 m=1.11e-02 o0=8.48e-03 o1=7.39e-03 o2=3.95e-03 o3=4.49e-03 o4=3.78e-03 o5=2.07e-03 o6=1.69e-03 o7=1.46e-03 o8=1.21e-03 final=8.37e-04  finite=1 ok best=532.1 ms
```

## What it does not show
Random / synthetic weights, a busy host (alternated runs are the evidence). Not tried: a threadgroup-resident Q for the VAE's 512-wide attention; a second, double-buffered A tile for the convolution (would need 48 KB of threadgroup memory; the limit is 32 KB).
