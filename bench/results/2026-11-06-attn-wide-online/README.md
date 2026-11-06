# The VAE's 512-wide attention in one pass (stage M slice 32, JUN-927)

Recorded 2026-11-06T21:35:53+0900 on 21mbp.local, M1 Max, load average 9.00 23.64 29.01. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`. GPU timestamps (slice 23), binaries alternated, three runs each; `attn_wide` over a 1024 px decode (16,384 tokens, head dimension 512).

## Result: a small gain, and what did not help
| variant | GPU ms per decode (pass 1 / pass 2) |
|---|---|
| two-pass (previous) | 1,108 to 1,386 / 1,081 to 1,143 |
| **single pass, online softmax, statistics by 4 threads per row, diagonal-matrix rescale (this slice)** | **976 to 1,190 / 928 to 1,022** (about 12 percent less) |
| the same with the four key sub-tiles sharing one Q load | 1,023 to 1,057 / 1,008 to 1,045 (not better: registers) |
| the same with K stored untransposed ([dim, key]) | 1,360 to 1,376 (worse: a 16,384-element stride) |
The online softmax of slice 26 carries over (the kernel is the 512-wide cousin of the UNet's), but the gain is only 12 percent here: the kernel is bound by loading the Q and K tiles from device memory (about 0.3 TMAC/s overall), not by the softmax. A register- or threadgroup-resident Q (32 queries x 512 x 2 bytes is the whole 32 KB of threadgroup memory) would be the next step and was not attempted. The attention is now about 11 percent of a 1024 px decode instead of 13.

## Gates (limits fixed in slice 10: final 1e-2, stages 2e-2)
```
mini (3104323 parameters, 64 px): cin=5.881e-04 mid=1.955e-03 l3=2.522e-03 l2=4.121e-03 l1=6.116e-03 b0=1.268e-02 final=5.886e-03 ok best=0.04 s
sdxl64 (49490179 parameters, 512 px): final=6.122e-03 ok best=0.70 s
sdxl128 (49490179 parameters, 1024 px): final=7.125e-03 ok best=5.31 s
mutant [attn-scale]: final error 1.892 (detected)
mutant [attn-wide-normalise]: final error 2.732 (detected)
mutant [upsample]: final error 4.401 (detected)
mutant [residual]: final error 3.056 (detected)
mutant [silu]: final error 3.028 (detected)
mutant [gn-variance-skipped]: final error 1.324 (detected)
mutant [gn-mean-wrong]: final error 1.689 (detected)
gate passed
```
- The online variant is slightly less accurate than the two-pass one in this kernel (small config final 5.9e-3 against 4.8e-3, last-block stage 1.27e-2 against 1.09e-2, 1024 px image 7.1e-3 against 5.9e-3): P is held unnormalised in half and divided by the running sum at the end. Still inside the limits. A mutant that removes that final division is detected (2.7). The float16-range stress still passes (small config 6.9e-3, the 512 px real-width decoder 9.3e-3), and the whole text-to-image render in both checkpoint layouts is unchanged (conditioning 1.0e-2 / 5.9e-3, steps 2.9e-2 / 2.7e-2).

## What it does not show
Synthetic / random weights, a busy host (the alternated comparison is what counts), and no trained VAE. With this, every kernel of the pipeline has been profiled and every one of the big ones tuned once; further gains would need a different structure (resident Q for the 512-wide attention, double-buffered tiles for the convolution and linear layers), and the remaining decisions are D1 and D4.
