# GroupNorm for large groups: partial sums over 64 tiles (stage M slice 31, JUN-927)

Recorded 2026-11-06T21:23:22+0900 on 21mbp.local, M1 Max, load average 16.00 17.32 22.35. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`. Timer: per-kernel GPU timestamps (slice 23), old and new binaries alternated, three runs each.

## Why and what
The profile of slice 30 left GroupNorm at 9 percent of the 1024 px VAE decode. The kernel runs one 128-thread threadgroup per group, so a layer has 32 threadgroups however big the image is, and it reads the group three times (mean, centred sum of squares, normalise): 29 GroupNorm dispatches took 0.59 to 0.82 s of GPU time per decode, about six times what the memory bandwidth alone would need. For groups of 262,144 elements or more (the VAE at 512 px and up; the UNet's groups are smaller and keep the old kernel) the recorder now issues five dispatches: partial sums over 64 tiles per group, the mean, the partial centred sums of squares (centred on that mean, as before, so no cancellation), the inverse standard deviation, and an element-parallel normalise (+ SiLU, or the token-major layout the attention needs).

## Result
GroupNorm GPU time per 1024 px VAE decode (`gn_silu_h` before; `gn_psum` + `gn_fin` + `gn_apply` after, plus the small groups still on the old kernel):

| | pass 1 | pass 2 |
|---|---|---|
| before | 591 to 817 ms | 802 to 822 ms |
| after | 195 to 199 ms | 192 to 196 ms |

About **75 percent less** (about 0.6 s of an ~8 s decode on a busy host).

Gates (limits as before: final 1e-2, stages 2e-2; the large-group path is exercised by the 512 px and 1024 px configurations; the small config's groups are below the threshold):
```
mini (3104323 parameters, 64 px): cin=5.881e-04 mid=1.856e-03 l3=2.748e-03 l2=3.797e-03 l1=6.018e-03 b0=1.091e-02 final=4.847e-03 ok best=0.04 s
sdxl64 (49490179 parameters, 512 px): final=6.168e-03 ok best=0.71 s
sdxl128 (49490179 parameters, 1024 px): final=5.934e-03 ok best=5.94 s
mutant [attn-scale]: final error 1.892 (detected)
mutant [upsample]: final error 4.403 (detected)
mutant [residual]: final error 3.05 (detected)
mutant [silu]: final error 3.029 (detected)
mutant [gn-variance-skipped]: final error 1.324 (detected)
mutant [gn-mean-wrong]: final error 1.689 (detected)
gate passed
```
- Two new mutants aimed at the new kernels, run on the 512 px configuration: the inverse standard deviation replaced by 1, and the mean replaced by 0: both detected (errors 1.3 and 1.7). The float16-range stress gate of slice 29 still passes (small config 6.5e-3; the 512 px real-width decoder 9.97e-3, **close to its 1e-2 limit** and 0.3e-3 above its previous 9.7e-3: summation order changed with the tiles; that limit is tight for this stress case, not a regression in the normal case, where the 512 px decoder went from 6.21e-3 to 6.17e-3 and the 1024 px one from 6.26e-3 to 5.93e-3).
- The whole text-to-image render in both checkpoint layouts passes with unchanged numbers (the small VAE inside it stays under the threshold), and the real-size 6.87 GB float16 file still renders finite (peak footprint 15.3 GB):
```
step 0: finite=True max|x|=59.43
step 1: finite=True max|x|=59.95
image 256x256: finite=True max|x|=2.16 mean|x|=0.206
recorded dispatches 4322
maximum resident set size: 6.93 GB
peak memory footprint: 15.33 GB
real: 0.00 GB
finite
build dir /var/folders/h_/bx1m0lv90qxb1sdrx5tqn8b80000gn/T//big-f16.c2wUf8
```

## What it does not show
Wall-clock gain of the decode is smaller than the GPU-time gain suggests (0.85 to 0.71 s at 512 px, 6.0 to 5.8 s at 1024 px on a busy host). Synthetic / random weights. The UNet's GroupNorm (2 percent of a call) is unchanged.
