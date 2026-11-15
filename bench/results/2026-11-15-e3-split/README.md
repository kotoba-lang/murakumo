# E3, third unit: unrolled GEMM tiles, split k, smaller passes, Qwen2.5-0.5B Q8_0, M1 Max (2026-11-15)

Follow-up to [the second E3 record](../2026-11-15-e3-gemm/README.md), Linear AWAI-2922. Same program and gate
(`bench/kernels/qwen2-metal/`), amu `f759f4f74`, `amu-program-metal`.

## pp512, same host, interleaved, three rounds

Load averages 17.7–28.7 (a busy host: other sessions were running). Raw outputs in [`raw/`](raw/); argv as in the
first record (murakumo: `@PREFILL@ 1`, `@GENIDS@ 512`, warm; MLX 0.31.1 `mlx_lm.benchmark --model
mlx-community/Qwen2.5-0.5B-Instruct-8bit -p 512 -g 1 -n 3`; llama.cpp 0.7.0 build 11669 `llama-bench -p 512 -n 0 -r 3`).

| Engine | Round 1 | Round 2 | Round 3 |
| --- | ---: | ---: | ---: |
| **murakumo** (3 runs per round) | **8208 / 8302 / 8270** | **8296 / 8170 / 8315** | **8307 / 8228 / 8322** |
| MLX 8-bit (prompt_tps) | 6239.3 | 6127.1 | 6245.2 |
| llama.cpp Metal | 8767.8 ±10.0 | 8775.6 ±64.5 | 8728.8 ±52.9 |

About 94–95 % of llama.cpp (up from 78 %), about 1.33x MLX. **The E3 target (≥ llama.cpp) is still not met.**

## What changed

- **The GEMM loops are fully unrolled** (`_Pragma("clang loop unroll(full)")`). Without it the compiler kept
  the accumulator arrays in memory once a simdgroup held more than 8 of them; this was the main reason the earlier
  64-row tiles had looked slower.
- **Each simdgroup accumulates a 32 x 32 slice** (4 x 4 matrices: 8 fragment loads per 16 multiply-accumulates instead
  of 6 per 8). The template takes the tile shape: gate|up (`gemmsw`) and the other GEMMs (`gemm`) both use 64 x 128
  tiles on 256 threads.
- **Split k for q|k|v, o and down.** These GEMMs have only 7 or 9 column tiles, so they split k four ways into
  partial buffers. RoPE sums the q|k|v partials. The next RMSNorm adds the o or down partials into the residual,
  so no dispatch is added. After the last layer, one RMSNorm of the last row does the same for the head.
- **GEMM inputs are half.** RMSNorm, attention and the SwiGLU epilogue store half, the value the GEMM rounded
  to before, so the result is unchanged. The GEMM reads half as many activation bytes.
- **RoPE reads a cos/sin table.** Layer 0 computes the table once per prefill, with the same precise
  pow/cos/sin as before, and stores it; layers 1–23 read it.
- **RMSNorm uses one 128-thread threadgroup per row** with float4 loads, instead of one simdgroup per row.

## Measured on the way (warm pp512 ms, or the isolated stage, same afternoon)

Isolated costs come from enabling one stage at a time against a run with every per-layer stage stubbed (about 2 ms).

| Variant | ms | Kept |
| --- | ---: | --- |
| second E3 unit (#685), whole prefill | 74.4–75.0 | |
| gate\|up alone, #685 | 38.0–38.9 | |
| gate\|up: threadgroup order, weight tile major | 37.6–38.6 | no change |
| gate\|up: `simdgroup_barrier` hints between fragment loads | 37.3–38.1 | no change |
| gate\|up: no device reads (loads replaced by constants) | 34.9–35.6 | diagnostic: reads are not the limit |
| gate\|up: 32 x 32 per simdgroup, 64 x 64 tile, not unrolled | 990 | no: accumulators spilled |
| same, unrolled | 33.6–34.1 | yes |
| same, double-buffered threadgroup memory, one barrier per step | 35.5–36.3 | no (lower occupancy) |
| gate\|up: 64 x 128 tile, 256 threads | **31.3–32.5** | yes |
| gate\|up: 128 x 128 / 64 x 256 | 43.4–44.0 / 38.2–39.0 | no |
| down / q\|k\|v / o: 32 x 32 or 32 x 128 tiles instead of 32 x 64 | slower | no |
| whole prefill with the above | 64.7–65.7 | |
| + RoPE table, one-threadgroup-per-row RMSNorm | 63.6–64.8 | yes |
| + half GEMM inputs | 62.6–63.5 | yes |
| + down split 4 on 64 x 128 tiles (split 2: 64.3–68.5, split 8: 62.3–62.4) | 62.1–62.2 | yes |
| + o split 4 | 60.2–60.4 (busier host later: 62.1–64.7) | yes |
| + q\|k\|v split 4 | 61.5–62.8, interleaved with the previous row's 61.8–64.7 | yes |
| attention with the 7 query heads of a kv head in one threadgroup, one simdgroup each, no barriers | 69 (attention alone 11 vs 5.3) | no: 128 threadgroups, long rows run serially |
| activations read straight from device memory into simdgroup matrices | 70.8–71.0 | no |
| weights stored n-major, loaded transposed | 66.6–67.1 | no |
| float4 dequantization of the weights | no change | no |
| first two layers in their own command buffer, committed before the rest is encoded | no change | no |

Isolated cost now (ms per prefill, minus the stubbed run): gate\|up ≈ 29 (≈ 7.4 TFLOPS), down ≈ 14.5 (≈ 7.4),
q\|k\|v ≈ 4.5, o ≈ 4.3, attention ≈ 5, RMSNorm and RoPE ≈ 3.5, stubbed run (embedding, head, argmax, submission) ≈ 2.
The M1 Max's peak is about 10.4 TFLOPS, so the large GEMMs run at about 71 % of it. The stages add up to
about 62 ms. llama.cpp takes 58.6 ms.

## Correctness

All gate rows pass ([`gate.txt`](gate.txt)): exact, long (129 tokens), chain-mutant, prefill-check (512 tokens,
argmax 198 both ways, max |logit difference| 0.0103 ≤ 0.05; the RMSNorm's new summation order moved it from 0.0083),
prefill-mutant, kernel-mutant, weight-mutant, digest, wrong-model, and a new **partial-mutant**: the RMSNorm that adds
the split-k partials dropping the last one changes the tokens (detected).
