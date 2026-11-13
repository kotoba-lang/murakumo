# 2026-11-13 · MacBook Pro M1 Max · 32 GB · macOS 26.4 · E0 same-day re-measure

Engine-plan E0 (docs/engine-plan.md): same-day table on the M1 Max for
Qwen2.5-0.5B Q8_0. Re-run of the 2026-11-03 record on the same host class
(Apple M1 Max, 32 GB, macOS 26.4). Kapitsa and the murakumo engine are NOT
in this round (see boundaries).

## Host

- Apple M1 Max, 32 GB, macOS 26.4, AC power.
- Busy host, left as it was: `uptime` load averages 15.32 15.22 16.56,
  swap used 6848 of 7168 MB (encrypted). GPU columns are stable across
  rounds; CPU-side figures move with load.
- MLX and llama.cpp rounds were run back to back, no interleaving.

## Models (same files as 2026-11-03)

- `bartowski/Qwen2.5-0.5B-Instruct-GGUF` → `Qwen2.5-0.5B-Instruct-Q8_0.gguf`,
  531,068,480 bytes, sha256
  `25130a98aa782284a7dabea0c23245b2fd371ed47244e79d78b8ec23245fdf96`
  (digest matches the 2026-11-03 record; `/tmp` copy, same bytes).
- MLX reads `mlx-community/Qwen2.5-0.5B-Instruct-8bit` (group-wise affine
  8-bit): `model.safetensors` 525,045,902 bytes (same byte count as
  2026-11-03). Same bit width, not the same file.

## Engines and argv

| Engine | Version | argv |
| --- | --- | --- |
| llama.cpp Metal | 0.7.0, build 11669 (`620d2225c`), Homebrew, ggml 0.27.0 | `llama-bench -m <gguf> -p 512 -n 128 -r 3 -o json` |
| MLX | mlx 0.31.1, mlx-lm 0.31.1 (Homebrew python3.11) | `mlx_lm.benchmark --model mlx-community/Qwen2.5-0.5B-Instruct-8bit -p 512 -g 128 -n 3` |

Raw files: [`raw/`](raw/) (`llama-metal-m1max.json`, `mlx-8bit-m1max.txt`).
Folded by hand into [`summary.json`](summary.json).

## Results

| Engine | Prefill, 512-token prompt | Decode, 128 tokens |
| --- | ---: | ---: |
| MLX, 8-bit | 6825.8 [6606.8–7021.7] | **250.8** [250.0–251.8] |
| llama.cpp Metal, Q8_0 | 9106.3 ±37.0 (json run; console run 9008 ±49) | **233.2** ±0.4 (console run 233.1 ±1.5) |

Prefill/decode are tok/s; MLX brackets are trial min–max (n=3),
llama.cpp is mean ± sd over the tool's own samples.

## Against 2026-11-03 (same host class, same builds)

| Engine | 2026-11-03 | 2026-11-13 | delta |
| --- | ---: | ---: | ---: |
| MLX prefill | 7191 [7140–7215] | 6826 [6607–7022] | −5.1 % (host load) |
| MLX decode | 249.4 [248.9–250.4] | 250.8 [250.0–251.8] | +0.6 % |
| llama.cpp prefill | 8842 [8788–8883] | 9106 ±37 | +3.0 % |
| llama.cpp decode | 218.4 [190.6–229.5] | 233.2 ±0.4 | within old range, top end |

GPU decode columns reproduce within a few percent under load. The numbers
to beat for E2 stand: MLX 250.8, llama.cpp 233.2 tok/s decode on 0.5B Q8.

## Boundaries (not measured)

- Kapitsa: not measured here. E0 wants it for Qwen3.5-4B 4-bit; needs a
  third-party checkout+build and its model. Next round.
- Greedy output tokens: not captured by either harness in bench mode.
  Correctness anchor stays the 2026-10-19 oracle (prompt
  `760 6511 314 9338 369` → greedy `314 746 600 220`) on the same GGUF
  digest. Capturing per-engine greedy continuations is a next-round item
  for the E0 gate ("each engine's greedy output recorded").
- murakumo engine: no route on this Mac (unchanged). The Oct-19
  MoltenVK/C-loader path no longer builds (loader removed 2026-10-22);
  the 71.2 tok/s figure stays a historical record, not a re-measured bar.
