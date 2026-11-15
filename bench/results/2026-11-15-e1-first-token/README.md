# E1: first tokens on the Apple GPU from Kotoba (2026-11-15, M1 Max)

[engine-plan](../../../docs/engine-plan.md) step E1. `bench/kernels/qwen2-metal/qwen2_metal.kotoba.in`, built by amu
`bin/amu compile --runtime-profile amu-program-metal` (amu `f759f4f74`, ADR 0405), runs Qwen2.5-0.5B-Instruct Q8_0
(bartowski GGUF, sha256 `25130a98aa782284a7dabea0c23245b2fd371ed47244e79d78b8ec23245fdf96`, 531,068,480 bytes) on the
GPU: SHA-256 check first, GGUF index, model-shape refusals, the data region in one Metal buffer, then embedding,
24 x (RMSNorm, q/k/v with bias, NEOX RoPE, KV cache, causal GQA attention, o-proj, RMSNorm, gate/up, SwiGLU, down),
final RMSNorm, tied head, argmax, all as Metal dispatches, one command buffer per token.

## Gate

`sh bench/kernels/qwen2-metal/run.sh <amu checkout> <model.gguf> python3`, all rows pass:

| Row | Result |
| --- | --- |
| exact | prompt `760 6511 314 9338 369` → greedy `314 746 600 220` (the 2026-10-19 oracle) |
| kernel-mutant | RoPE rotation with one sign flipped → `369 369 369 369` |
| weight-mutant | the sign bit of one fp16 scale in token 314's embedding row flipped (own digest) → `314 400 72 284` |
| digest | a pinned digest one character off refuses before any token (exit 133) |
| wrong-model | the S1 synthetic fixture (Qwen2 KVs, small shapes) refused: `tensor shape, dtype or span blk.0.attn_norm.weight` |

`ref_forward.py` (numpy, local oracle only, not on the engine path) re-derives the same four tokens from the same file.

## Decode rate (diagnostic, not a benchmark)

32 greedy steps after the 5-token prompt, three runs, load average about 9 (not a quiet host):
184.73 / 184.11 / 184.15 ms → **about 174 tok/s** (5.75 ms per token, including one CPU readback and one command
buffer per token). Raw lines: `decode32-run*.txt`. Same Mac, 2026-11-13: llama.cpp Metal 233.2, MLX 8-bit 250.8.

## Not covered

- Tokens after the fourth are not compared with an oracle (the gate checks four).
- Prompt processing is one token at a time (E3 batches it); the KV cache holds 64 positions.
- Kernels are hand-written MSL inside the Kotoba program (engine-plan D2); E7 replaces them with Amu-emitted kernels.
- Not a quiet-host, same-day comparison with llama.cpp / MLX / Kapitsa (E0/E2).
