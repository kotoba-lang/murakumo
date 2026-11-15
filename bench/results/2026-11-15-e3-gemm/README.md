# E3, second unit: prefill GEMM work, Qwen2.5-0.5B Q8_0, M1 Max (2026-11-15)

Follow-up to [the first E3 record](../2026-11-15-e3-prefill/README.md), Linear AWAI-2922. Same program and gate
(`bench/kernels/qwen2-metal/`), amu `f759f4f74`, `amu-program-metal`.

## pp512, same host, interleaved, three rounds

Load averages 10.5–11.8 (not quiet). Raw outputs in [`raw/`](raw/); argv as in the first record.

| Engine | Round 1 | Round 2 | Round 3 |
| --- | ---: | ---: | ---: |
| **murakumo** (warm, 3 runs per round) | **6587 / 6858 / 6685** | **6680 / 6695 / 6790** | **6722 / 6717 / 6558** |
| MLX 8-bit (`-p 512 -g 1 -n 3`, prompt_tps) | 6430.1 | 5952.4 | 6289.1 |
| llama.cpp Metal (`llama-bench -p 512 -n 0 -r 3`) | 8593.0 ±49.7 | 8613.6 ±33.1 | 8699.1 ±65.5 |

Ahead of MLX in every round; about 78 % of llama.cpp. **The E3 target (≥ llama.cpp) is still not met.**

## What changed

- The GEMM stages activations and dequantized weights as half in contiguous 8 x 8 tiles (weights k-major inside a
  tile), reads its share of the next Q8_0 block into registers before the barrier so the reads overlap other
  simdgroups' arithmetic, and starts its accumulators from Y (add), from a bias tile (bias) or from zero, then stores
  straight to device memory: no staged epilogue, 6 KB of threadgroup memory.
- **SwiGLU is fused into the gate|up GEMM** (`gemmsw`, a second instantiation of the same template): each simdgroup
  owns 8 gate columns and the matching 8 up columns, and its epilogue writes silu(gate) * up through a 512-byte slice of
  the staging buffer. The GEMM's output halves and the separate SwiGLU pass is gone (8 dispatches per layer).
- The unused SwiGLU and 4-row attention kernels are removed.

## Measured on the way (warm pp512 ms, same day)

| Variant | ms | Kept |
| --- | ---: | --- |
| first E3 unit (#684) | 81–83 | |
| register prefetch of the next tile | 85 | no |
| half both operands, blocked tiles, reads before the barrier | 77–78 | yes |
| barrier-free attention, one simdgroup per 8 rows | 81 | no (attention went from 6 to 9 ms) |
| weight-tile-major threadgroup order | 77.8 | no change |
| SwiGLU through `thread_elements()` | 1170 | no: the matrix's elements are 64 floats per thread at source level, the accumulators spilled |
| SwiGLU through threadgroup memory, template instance | **76.3** | yes |
| 64-token tiles on 8 simdgroups | 80 | no |

Isolated GEMM cost before the SwiGLU fusion (enabling one GEMM at a time against a run with all four stubbed, ms per
prefill): q|k|v 3.8 (≈ 6.7 TFLOPS), o 2.3 (≈ 8.6), gate|up 37.3 (≈ 5.7), down 13.7 (≈ 7.8). Attention costs about
6 ms. The gap to llama.cpp is mostly the gate|up GEMM and the fixed cost of about 170 dependent dispatches.

## Correctness

All gate rows pass, including prefill-check (512 tokens, argmax 198 both ways, max |logit difference| 0.0083 ≤ 0.05)
and prefill-mutant (causal mask off by one, detected); exact, long (129 tokens) and every mutant as before.
