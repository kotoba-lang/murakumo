# Qwen3.5-4B decode speed, third pass (2026-11-16)

Linear AWAI-2938. Follow-up to [the second pass](../2026-11-16-qwen35-decode/README.md). ms/token comes from 64 decode
steps after the 5-token prompt, with builds run interleaved in the same minutes. The host was busy (load averages
10–43; other sessions used the GPU).

## Kept

- **Each simdgroup owns its rows over the whole row.** Before, a 256-thread threadgroup took 4 rows and its 8
  simdgroups split each row into 256-value steps, then met in threadgroup memory. A 2,560-wide row has only 10 steps,
  so 8 simdgroups ran 2 rounds with 6 of them idle in the second. Now a threadgroup has 4 simdgroups, each owning 4
  rows over the whole row (16 rows per threadgroup), with no threadgroup reduction and no barrier. Q8_0 and Q6_K rows
  over 3,072 values (the 4,096-wide output projections and the 9,216-wide down projection) are still split between
  2 simdgroups. Whole decode: 18.1–18.9 ms against 20.6–21.0 for the previous build.
- **Q4_K and Q5_K read 16 bytes per lane** (`mvk_q4_k`, `mvk_q5_k`). Each byte of a Q4_K block holds a value of
  sub-block 2m in its low nibble and of sub-block 2m + 1 in its high nibble. Before, two lanes loaded the same 8 bytes
  and each kept one nibble. Now 8 lanes cover a block: each loads 16 bytes once and uses both nibbles against 8 float4
  of activations, and a simdgroup walks 4 blocks per step. Whole decode: 17.6–17.8 ms against 18.7–19.2.

Whole decode: **17.6–18.0 ms/token** in the build comparison, against 20.6–21.2 for #693 in the same minutes.

## Tried and rejected

| Variant | Result |
| --- | --- |
| row steps split evenly (largest divisor of the step count up to 8 simdgroups, 5 for 2,560 columns) | 20.0 against 20.9: kept only as the stepping stone to whole-row simdgroups |
| 8 rows per simdgroup | 33 ms (register spill) |
| 2 or 8 simdgroups per threadgroup; 2 rows per simdgroup | 17.7–19.8, none clearly better than 4 × 4 |
| RMSNorm fused into the matvecs (each simdgroup sums x² while it reads x and scales its result; the norm weight is multiplied into x on load) | 18.6–19.9 against 17.6–18.0 |
| normalized activations stored as half (half the x loads and registers) | 18.1–18.5 against 17.6–17.7 |

Stubs, for sizing only (wrong tokens, not kept): without the conv, delta-rule and gated-norm dispatches, 16.9–17.2 ms;
without SwiGLU, no change; with every non-matvec stage and every barrier removed, 14.3–14.8 ms. The matvecs alone
read 2.74 GB per token at about 190 GB/s. The rest (about 3 ms) is barriers between dependent stages and the small
stages between them, so fewer stages per layer is the next lever.

## Same-hour comparison (plain greedy decode, 128 tokens, tok/s)

Raw outputs are in [`raw/`](raw/), load averages in [`raw/uptime.txt`](raw/uptime.txt) (10.8–20.2).

| Engine | Round 1 | Round 2 | Round 3 |
| --- | ---: | ---: | ---: |
| murakumo, run between the other engines | 49.9 | 41.2 | 44.0 |
| llama.cpp b11669, `llama-bench -p 0 -n 128 -r 3` (same GGUF) | 38.6 ±9.4 | 38.7 ±8.0 | 35.2 ±3.3 |
| MLX 0.31.1, `mlx_lm.benchmark --model mlx-community/Qwen3.5-4B-4bit -p 8 -g 128 -n 3` | 55.5 | 53.3 | 56.2 |

The same murakumo binary, run three more times on its own right after (load average rising to 20), gave 45.8, 43.8 and
41.6 tok/s. Every murakumo run produced the same 128 greedy tokens as the previous pass.

This hour every engine ran slower than in the second pass (MLX 53–56 against 68–71), so compare within a round:
murakumo is at 74–89 % of MLX here, against 56–63 % in the second pass. It is still behind MLX.

## Correctness

The gate ([`gate.txt`](gate.txt)) passes every row, including a new **q4k-mutant**: the 16-byte Q4_K/Q5_K matvec with
the scales of its low- and high-nibble sub-blocks swapped changes the tokens.
