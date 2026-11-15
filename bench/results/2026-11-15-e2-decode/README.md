# E2: Metal decode, Qwen2.5-0.5B Q8_0, M1 Max (2026-11-15)

[engine-plan](../../../docs/engine-plan.md) step E2, Linear AWAI-2917. Same program as E1
(`bench/kernels/qwen2-metal/qwen2_metal.kotoba.in`, built by amu `bin/amu compile --runtime-profile amu-program-metal`,
amu `f759f4f74`), same GGUF (sha256 `25130a98…`), greedy output unchanged.

## Same-day comparison (one host, interleaved, three rounds)

MacBook Pro M1 Max 32 GB, macOS 26.4, AC power. **Not a quiet host**: load averages 5.9–7.8 during the rounds. Rounds
ran llama.cpp → MLX (512-token prompt) → MLX (8-token prompt) → murakumo, three times. Raw outputs in [`raw/`](raw/).

| Engine | Version, argv | Round 1 | Round 2 | Round 3 |
| --- | --- | ---: | ---: | ---: |
| **murakumo E2** (Kotoba / Amu, Metal) | 5-token prompt, 128 greedy tokens, decode wall time | **292.6** | **292.3** | **294.3** |
| MLX 8-bit | mlx 0.31.1, mlx-lm 0.31.1, `mlx_lm.benchmark --model mlx-community/Qwen2.5-0.5B-Instruct-8bit -p 8 -g 128 -n 3` | 253.0 | 250.7 | 252.4 |
| MLX 8-bit (E0 argv) | same, `-p 512 -g 128 -n 3` | 221.7 | 241.2 | 234.2 |
| llama.cpp Metal | 0.7.0 build 11669 `620d2225c`, `llama-bench -m <gguf> -p 0 -n 128 -r 3 -o json` | 186.6 ±39.0 | 168.9 ±33.9 | 155.8 ±30.3 |

Decode tok/s. On this host and day murakumo's decode is faster than both. Caveats, all of them material:

- llama.cpp ran far below its 2026-11-13 figure on the same Mac (233.2 ±0.4) and with a large spread; the load hurt it
  more than the others. Its quiet-host number may be well above what it showed today.
- MLX reads `mlx-community/Qwen2.5-0.5B-Instruct-8bit` (group-wise affine 8-bit), not the same file (as in E0).
- murakumo keeps two command buffers in flight during decode (the next step's buffer reads the token the previous
  argmax left on the GPU) and reads each token back as soon as its own buffer completes. The `-p 8` MLX row is the
  closest context match to murakumo's 5-token prompt; llama-bench tg128 starts from an empty context.
- Not claimed through the AWAI-18 claim gate; a quiet-host round is still to do.

## Correctness

`sh bench/kernels/qwen2-metal/run.sh <amu> <model.gguf> python3`, all rows pass:

| Row | Result |
| --- | --- |
| exact | `760 6511 314 9338 369` → `314 746 600 220` (2026-10-19 oracle) |
| long | 129 greedy tokens equal the continuation pinned in run.sh (recorded once from `ref_forward.py`, numpy) |
| chain-mutant | decode that ignores the previous argmax → `314 314 314 0 0 0 0 0` |
| kernel-mutant | RoPE sign flipped → `369 369 369 369` |
| weight-mutant | one fp16 scale sign flipped in token 314's row → `314 400 72 284` |
| digest | one character off → refused before any token |
| wrong-model | S1 fixture → refused by the shape check |

## How it got there (decode ms per token, loaded host, each step gated on the same tokens)

| Change | ms/token | Note |
| --- | ---: | --- |
| E1 as merged (#682) | 5.75 | 32 steps; CPU encode 0.17 ms of it, the rest GPU |
| Q8_0 matvec: 4 rows per simdgroup, 4 lanes per block, x slice in registers | 5.0 | the big head matvec now reads at about 410 GB/s |
| fusion: 14 → 6 dispatches per layer (norm into q|k|v and gate|up, RoPE + KV store, SwiGLU into down, norm into head) | 4.8 | |
| attention: one position per thread instead of a barrier per position | 3.95 | attention was about 48 µs per layer |
| `ushort` quant loads | no change | reverted |
| two command buffers in flight, token chained on the GPU | 3.59 | |
| KV cache 64 → 256 positions, measured at 128 decode steps | 4.49 | attention grows with context |
| split-K attention (256 threads per head, one exp per position) | 3.41 | 128 steps; 3.32 at 32 steps |

Per-kind timing (dispatch kept, its work removed, 32 steps) located the costs; see AWAI-2917 for the numbers.

## Not covered

- Quiet-host round and the AWAI-18 claim gate; Kapitsa (E0 wants Qwen3.5-4B for it).
- Prompt is still one token per command buffer (E3); contexts over 256 positions.
- Kernels are hand-written MSL inside the Kotoba program (engine-plan D2); E7 replaces them.
