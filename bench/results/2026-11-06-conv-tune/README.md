# Convolution kernel retune in the runtime-parameter conv (stage M slice 12, JUN-927)

Recorded 2026-11-06T18:11:07+0900 on 21mbp.local, M1 Max, load average 8 to 13 from other agents' processes throughout.

## Where the SDXL time goes (ablation, 768 px, load-affected)
Making one kernel return immediately in the slice-11 program and subtracting from the full run (about 1.7 to 1.9 s per call): the 2D convolution kernel accounts for about 0.55 to 0.7 s (457 GMAC, ~0.75 TMAC/s), the linear layers for about 0.65 to 0.9 s (1,236 GMAC, ~1.6 TMAC/s), attention for nothing measurable (within noise), and everything else (norms, GEGLU, concat/untokenise, element-wise) about 0.3 to 0.4 s. The ablation numbers move by +/-0.2 s between runs, so these are orders, not figures. It shows the linear layers are the largest block, the convolution the least efficient, attention not worth tuning at these sizes.

## What changed
The runtime-parameter `conv` kernel (used by the mini UNet and the SDXL program; the constant-parameter slice-4 kernel was already at 1.9 TMAC/s) got:
1. the output-pixel decode (division and modulus by the width) hoisted out of the K loop: a thread's output pixel is fixed across it;
2. constant-divisor decoding of the (channel, ky, kx) index for 3x3 and 1x1;
3. a 64-wide K tile (was 32): half the barriers, and the weight tile loaded as 8-byte `half4` vectors when the K extent is a multiple of 4 (scalar fallback otherwise).
Threadgroup memory is exactly 32 KiB (the limit).

## Evidence
- Correctness gate, unchanged: `sh bench/kernels/unet-metal/run.sh` (mini UNetModel against ComfyUI v0.44.0 on CPU): final error 1.368e-03 (limit 1e-2), worst stage 4.26e-03 (limit 2e-2), the same figures as before the change; all three mutants detected. The weights buffer addresses used with `half4` loads came out aligned in this gate; an unaligned offset was not exercised.
- Mini graph best of 20: 7.52 ms before, 6.62 ms after (runs made minutes apart, not interleaved).
- SDXL-shaped call, 512 px, 8 interleaved runs of each binary: new 0.98 to 1.10 s (median about 1.07), old 1.23 to 1.34 s with one 0.84 s outlier (median about 1.28): about 15 percent faster at the median. The best-of figures contradict each other (old 0.84 vs new 0.98), and at 768 px six interleaved rounds put the old at 1.66 to 2.33 s and the new at 1.83 to 2.17 s with no separable difference. The host's load makes a precise figure impossible here, so the claim is "not slower, probably modestly faster", not a number.

## Not done
- No quiet-host measurement; the per-kernel rate of the new conv was not measured on its own. Linear (the biggest block) was not touched. The slice-4 harness (constant parameters) was not rerun. Hand-written MSL, no pipelining of loads and compute (double buffering) yet.
