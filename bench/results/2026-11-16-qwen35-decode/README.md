# Qwen3.5-4B decode speed, second pass (2026-11-16)

Linear AWAI-2938. Follow-up to [the first pass](../2026-11-15-qwen35-decode/README.md). Unless noted, ms/token comes from
64 decode steps after the 5-token prompt, two runs per build. The host was busy (load averages 10–160).

## Kept

- **Tokens chained on the GPU.** Each decode step's command buffer, which reads the token the previous argmax left in
  the arena, is committed before the CPU waits for the previous step's buffer, so the GPU does not idle between tokens.
  On the Q4_K/Q5_K-only stage, against the previous version measured in the same session, it is 15.6 ms against
  16.5–17.5.
- **No branch in the matvec's row loop.** Rows past a matrix's end repeat its last row and are not stored. Q4_K/Q5_K
  stage: 14.9 ms against 15.6.

Whole decode: 19.6–19.7 ms/token in the build comparison, against 21.6 for #692.

## Tried and rejected

| Variant | Result |
| --- | --- |
| Q4_K/Q5_K: one lane reads 4 bytes once for a low-nibble and a high-nibble sub-block | Q4_K/Q5_K stage 18.5 ms against 15.6 |
| two 256-value steps per loop iteration (more loads in flight) | 18.0 against 14.9 (register pressure) |
| RMSNorm fused into the matvecs (each threadgroup normalises x itself) | whole decode 24.2 against 19.6: the head's 62,080 threadgroups each recompute the norm |

## Same-hour comparison (plain greedy decode, tok/s)

Raw outputs are in [`raw/`](raw/).

| Engine | Round 1 | Round 2 | Round 3 |
| --- | ---: | ---: | ---: |
| murakumo, 128 steps, run between the other engines | 42.6 | 38.3 | 38.6 |
| llama.cpp b11669, `llama-bench -p 0 -n 128 -r 3` (same GGUF) | 35.5 | 35.7 | 35.2 |
| MLX 0.31.1, `mlx_lm.benchmark ... -p 8 -g 128 -n 3` (mlx-community 4-bit) | 67.9 | 70.7 | 67.8 |

The same murakumo binary, run on its own right after these rounds, gives 50.5, 45.2 and 50.5 tok/s. Run between the
others it is slower; the cause (clocks, memory pressure) is not determined.

murakumo stays ahead of llama.cpp on the same file. It is still behind MLX, which reads about 2.4 GB per token against
this file's 2.74 GB.

## Correctness

The gate ([`gate.txt`](gate.txt)) passes every row.
