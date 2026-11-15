# Qwen3.5-4B on the Metal route through the engine library (2026-11-15)

Second and third slices of Linear AWAI-2938: the Metal kernels for the K-quants and the whole-model decode. They are
built in the new engine library (`engine/src`, [docs/engine-profiles.md](../../../docs/engine-profiles.md)),
compiled with `bin/amu compile ENTRY --source-path engine/src --runtime-profile amu-program-metal` (amu `f759f4f74`).
The model is `unsloth/Qwen3.5-4B-GGUF` @ e87f176, `Qwen3.5-4B-Q4_K_M.gguf` (sha256 `00fe7986…`), run on an M1 Max
(32 GB).

## Correctness

The gate is [`bench/kernels/qwen35-metal/run.sh`](../../kernels/qwen35-metal/run.sh); its output is in [`gate.txt`](gate.txt). Every row passes:

- **exact:** with prompt `760 6511 314 9338 369`, all 16 greedy tokens equal llama.cpp b11669's
  (`11751 13 198 32 13 2912 198 33 13 3439 198 15666 25 198 32 271`).
- **layer4:** after 4 layers (3 delta-net layers and the first gated-attention one), the residual is within 4.4e-7 of
  the numpy oracle's (`ref_forward35.py --layers 4`, values pinned in the gate).
- **Mutants, all detected:**
  - delta-net value heads mapped by hv / 2 (the repeat order);
  - conv1d without its state;
  - attention without its sigmoid gate;
  - one Q6_K scale index swapped;
  - RoPE frequencies doubled. This one is caught by the layer4 residual (off by 0.0053); the 16 greedy tokens of this
    short prompt do not change.
- **digest:** a pinned digest one character off is refused.
- **wrong-model:** the Qwen2-architecture fixture is refused by the qwen35 profile.

A bug found on the way: Q6_K blocks are 210 bytes, not a multiple of 4, so `uchar4` loads were misaligned on every other
block. The matvec now reads Q6_K bytes one at a time; the embedding row used per-value reads and was right from the
start.

## Decode speed (plain greedy, no speculation)

Same host, interleaved, two rounds, load averages 37–43 (a busy host). Raw outputs are in [`raw/`](raw/).

| Engine | Round 1 | Round 2 |
| --- | ---: | ---: |
| **murakumo** (128 decode steps after the 5-token prompt, one command buffer per token) | **39.1** | **42.5** |
| llama.cpp b11669, `llama-bench -p 0 -n 128 -r 3` (same GGUF) | 35.4 | 34.0 |
| MLX 0.31.1, `mlx_lm.benchmark --model mlx-community/Qwen3.5-4B-4bit -p 8 -g 128 -n 3` | 60.8 | 62.0 |

murakumo is ahead of llama.cpp on the same file and behind MLX (a different 4-bit pack). Kapitsa decodes speculatively
(78–138 tok/s in E0), so it is not a plain-decoding comparison. This first version keeps every kernel simple:

- one matvec row per simdgroup;
- about 14 dispatches per layer and one command buffer per token, waited before the next.

Fusion, multi-row matvecs, overlapping command buffers (E2's techniques) and prefill are next.

## What the profile chose

Every matvec's kernel comes from that tensor's dtype in the file. In this Q4_K_M file:

- attn_qkv and ssm_out are Q5_K;
- attn_gate, ffn_gate and ffn_up are Q4_K;
- ffn_down and token_embd are Q6_K in this layer, and Q4_K or Q6_K vary by layer for other roles;
- ssm_alpha and ssm_beta are Q8_0.

The kind of each layer (Gated DeltaNet or gated attention) comes from `full_attention_interval`, and every dimension
is checked against the kernels' envelope.
