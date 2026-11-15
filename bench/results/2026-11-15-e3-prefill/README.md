# E3: batched prefill on Metal, Qwen2.5-0.5B Q8_0, M1 Max (2026-11-15)

[engine-plan](../../../docs/engine-plan.md) step E3, Linear AWAI-2922. `bench/kernels/qwen2-metal/qwen2_metal.kotoba.in`
(amu `f759f4f74`, `amu-program-metal`) now runs the whole prompt through the model in one command buffer
(`@PREFILL@` 1, the default): embedding of all prompt rows, then per layer RMSNorm rows, a Q8_0 GEMM for q|k|v with
biases, RoPE + KV store for all rows, causal GQA flash-style attention on simdgroup matrices, the o GEMM into the
residual, RMSNorm rows, the gate|up GEMM, SwiGLU, the down GEMM into the residual; the last row goes through the head
and argmax. Decode is unchanged (E2).

## pp512, same host, interleaved, three rounds

Load averages 9.6–11.0 (not quiet). Raw outputs in [`raw/`](raw/). murakumo: warm prefill (one warm-up prefill first,
as llama-bench warms up), 512 synthetic prompt ids `(i * 7919 + 100) mod 151000`, three runs per round.

| Engine | argv | Round 1 | Round 2 | Round 3 |
| --- | --- | ---: | ---: | ---: |
| **murakumo E3** | `@PREFILL@ 1`, `@GENIDS@ 512` | **6256 / 6227 / 6265** | **6283 / 6262 / 6261** | **6314 / 6250 / 6242** |
| MLX 8-bit | mlx 0.31.1, `mlx_lm.benchmark --model mlx-community/Qwen2.5-0.5B-Instruct-8bit -p 512 -g 1 -n 3` (prompt_tps) | 6269.9 | 6075.8 | 6210.8 |
| llama.cpp Metal | 0.7.0 build 11669, `llama-bench -m <gguf> -p 512 -n 0 -r 3 -o json` | 8643.1 ±54.4 | 8590.9 ±61.5 | 8632.1 ±63.1 |

murakumo's prefill is level with MLX and about 73 % of llama.cpp's. **The E3 target (≥ llama.cpp) is not met yet.**
Token by token, the same 512-token prompt would take about 512 × 3.4 ms ≈ 1.7 s; the batched prefill takes about 82 ms.

## Correctness

`sh bench/kernels/qwen2-metal/run.sh <amu> <model.gguf> python3`, all rows pass (every row now uses the batched
prefill unless it says otherwise):

| Row | Result |
| --- | --- |
| exact | `314 746 600 220` |
| long | 129 tokens equal the pinned continuation |
| chain-mutant | detected (`314 314 314 0 0 0 0 0`) |
| **prefill-check** | 512-token prompt, batched vs one token per step: argmax 198 both ways, max \|logit difference\| 0.0088 (limit 0.05) |
| **prefill-mutant** | flash attention causal mask off by one: argmax 198 vs 93472, max difference 12.6 (detected) |
| kernel-mutant, weight-mutant, digest, wrong-model | as in E2 |

`ref_forward.py` (numpy, local oracle, run once) gives the same next token, 198, for the 512-token prompt. The 0.0088
comes from rounding dequantized weights to half in the GEMM's threadgroup memory (ggml's Metal GEMM stages weights
as half too); with float staging it was 2.7e-5.

## How it got there (warm pp512, ms)

| Change | ms | Note |
| --- | ---: | --- |
| first batched path: Q8_0 GEMM 32 x 64 tiles, float, RMSNorm and SwiGLU applied while staging | 228 | (cold 267) |
| bug: GEMM parameters past byte 72 overwritten by the dispatch size in `disp` | — | M > 32 was wrong; fixed, guard added |
| RMSNorm and SwiGLU as their own passes (the GEMM had recomputed them per tile) | 131 | |
| weights staged as half | 122 | logits move by about 1e-2 |
| two Q8_0 blocks per stage | 157 | reverted |
| untransposed half staging with vector stores | 120 | |
| prefill attention, 4 query rows per threadgroup (512 threads) | 114.6 | 256: 113–118, 1024: 124 |
| 8 x 8 blocked threadgroup layout; half activations | no change | reverted |
| one 8 KB threadgroup buffer for both stages and the output tile (occupancy) | 99–102 | |
| non-bias GEMMs store straight to device (add mode starts from Y) | 97 | |
| 64 x 64 tile on 8 simdgroups; 32 x 32 tile on 64 threads; tile order | slower / no change | reverted |
| flash-style attention on simdgroup matrices (8 rows, 32-position chunks, online softmax) | 94 | |
| its softmax over all 4 simdgroups instead of 8 threads | **82** | |

At 82 ms the GEMMs take about 60 ms (about 6 TFLOPS of the 367 GFLOP), attention about 15 ms, the rest about 5 ms.

## Not covered

- Beating llama.cpp's prefill (≈ 8,600 here): the GEMM needs roughly another 1.6x.
- A quiet-host round; prompts longer than 512 tokens (one prefill batch) or contexts over 1024 positions.
- Kernels are hand-written MSL inside the Kotoba program (engine-plan D2).
