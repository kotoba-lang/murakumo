# GPU-side profile of the SDXL-shaped UNet, and a specialised 3x3 convolution (stage M slice 23, JUN-927)

Recorded 2026-11-06T19:48:53+0900 on 21mbp.local, M1 Max, load average 8.77 7.77 10.64. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, profile `amu-program-metal`.

## 1. A timer that other processes' CPU load cannot stretch
Slices 12 and 13 could not tell a kernel change from noise because every measurement was wall-clock on a host whose load average was 9 to 50. `bench/kernels/unet-sdxl-profile` runs the slice-11 program (synthetic weights, ~2,100 kernels) with each dispatch in its own command buffer and reads the buffer's `GPUStartTime` / `GPUEndTime` through `valueForKey:` -> `NSNumber` -> `description` (the boot interface returns only integers, so the double is parsed from text), giving microseconds of GPU time per kernel. GPU sharing with other processes still inflates it (the same binary showed 5 to 25 percent between two passes), but comparisons made by alternating binaries and taking the minimum were stable: three alternations of the generic and the specialised convolution gave 309 to 320 ms against 233 to 242 ms of convolution time per call.

## 2. Where the GPU time goes (512 px, one call, minimum of two passes; `sh bench/kernels/unet-sdxl-profile/run.sh <amu> 64`)
```
latent 64x64 (512 px)
  kernel        count     pass1 ms     pass2 ms
  linear_t        722        307.2        375.2
  conv_k3s1        38        210.9        231.2
  attn_hc         140         47.4         55.7
  conv             13         21.0         19.7
  gn_silu_h        35         11.3         10.1
  temb_bias2       17          8.9          9.8
  geglu_h          70          4.8          7.1
  ln_h            210          5.0          6.6
  gn_tok_h         11          1.5          1.5
  matvec            4          0.8          0.7
  untok_add        11          0.5          0.7
  upsample_h        2          0.2          0.2
  timestep          1          0.0          0.0
  vadd              1          0.0          0.0
  TOTAL                      619.5        718.4   (model kernels)
  setup                       45.4         56.0   (synthetic-weight fill and fp32->fp16 conversion)
    slowest: dispatch 1575 (conv_k3s1) 13.7 ms
    slowest: dispatch 735 (conv_k3s1) 11.3 ms
    slowest: dispatch 1850 (conv_k3s1) 17.3 ms
    slowest: dispatch 2063 (conv_k3s1) 13.4 ms
    slowest: dispatch 1297 (conv_k3s1) 12.4 ms
build dir /var/folders/h_/bx1m0lv90qxb1sdrx5tqn8b80000gn/T//unet-sdxl-profile.ZzFXAj
```
- The linear layers (722 dispatches) are now the largest block, the convolutions second, attention third (about 7 percent), and everything else (normalisations, GEGLU, time embedding) about 5 percent. The earlier ablation estimate (slice 12) had the shape right (linear 0.65 to 0.9 s and convolution 0.55 to 0.7 s of ~1.8 s at 768 px) but not the attention share, which is small but measurable here.
- The slowest single dispatches are 3x3 convolutions at the 320-wide level, 13 to 23 ms each.

## 3. The change: a specialised 3x3 stride-1 convolution
`conv_k3s1` (source of truth `bench/kernels/conv-k3s1-metal/conv_k3s1.msl`, pasted into the five programs that use the general `conv`) computes the nine tap-validity bits and the pixel offset once per thread and advances the (channel, tap) index incrementally instead of dividing per element, and takes the 3x3 stride-1 case (38 of the 51 convolutions of the graph, most of the work); the other 13 (1x1 skips, stride-2 downsamples) keep the general kernel (a text-specialised 1x1 variant crashed the host program and was not pursued).
- GPU time of the convolutions per call at 512 px: **309 to 341 ms before, 233 to 262 ms plus ~21 ms for the other 13 after** (alternating runs, three each), i.e. about 15 to 20 percent less convolution time and 8 to 10 percent of the whole call.
- Correctness, all against ComfyUI's own classes, errors identical to the digit to the previous kernel's (the accumulation order is unchanged): the SDXL-topology UNet gate at model_channels 64 (21 stages, worst 8.3e-3; the five mutants now include one that breaks the new kernel's validity mask and is detected: 1.04), the VAE decoder gate (512 px 6.2e-3, 1024 px 6.3e-3, mutants detected), the mini UNet gate (final 1.37e-3), and the whole text-to-image render gate (conditioning 1.0e-2, steps 3.6e-2, image 1.7e-2, seven mutants detected). The mini-UNet gate's mutants now replace every occurrence of their text so they also reach the new kernel (its residual-convolution mutant had silently stopped biting).
- Wall-clock per UNet call from the slice-11 timing program, on a host with a load average of about 8 (it was 20 to 46 for the earlier figures): **0.62 s at 512 px, 1.52 s at 768 px, 2.87 s at 1024 px** (before: 0.71, 2.1, 4.1 s under heavier load). That puts the 50 calls of a 25-step guided render at 768 px at about 76 s, still not faster than the 72 s ComfyUI took on a smaller GPU; the VAE decode at 1024 px took 6.0 s (was 9.9 s).

## What it does not show
- Synthetic weights in the timing and profile programs; the correctness gates use random weights at smaller widths.
- GPU time was measured with other processes sharing the GPU, so the absolute microseconds are upper bounds.
- The linear layers, now the largest block at about 1.7 TMAC/s against an f16 peak near 5, are untouched; so is the attention kernel.
