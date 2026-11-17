# Qwen3.5-4B: a pass of up to 16 tokens (2026-11-17)

Linear AWAI-3008 (under AWAI-2938). This is the first step toward drafted decoding with the DFlash drafter
(z-lab/Qwen3.5-4B-DFlash). That drafter proposes a block of 16 tokens, and the main model checks the whole block in
one pass. The same pass now runs the prompt 16 tokens at a time.

## What changed

- **Matvecs over up to 16 tokens use simdgroup matrices** (`mm_q8_0`, `mm_q4_k`, `mm_q5_k`, `mm_q6_k`, `mm_bf16`).
  - A 128-thread threadgroup takes 64 rows.
  - For each step of 64 values, its threads dequantize the 64 × 64 weight tile into threadgroup memory as half and
    load the 16 × 64 activations as float.
  - Each simdgroup multiplies its 16 rows by the 16 tokens as 2 × 2 tiles of 8 × 8, with float sums.
  - A matrix with too few row tiles to fill the GPU is split along the row into partial sums. `mmr` adds them up in
    split order, so the result does not depend on scheduling.
- **One Gated DeltaNet dispatch covers all the tokens of a pass** (`dnfn`).
  - The conv window and the delta-rule state stay in registers across the tokens.
  - The state is read once and written once, after the last token, to the next slot.
  - It runs 512 threads per value head, each simdgroup holding 8 rows.
- **The small per-token kernels take the token as grid y**: RMSNorm, SwiGLU, q/k norm with RoPE, attention, argmax.
  A pass of n tokens no longer needs n dispatches each.
- **The prompt runs 16 tokens per pass.** Only its last token goes through the head.
- **Pipelines that cannot run their threadgroup size are refused at load.** The first `dnfn` used 1024 threads, but
  its pipeline allowed only 832. The GPU ran it partly and silently, and the tokens were wrong. Every pipeline is now
  checked against the threadgroup size its dispatches use.

## Prompt speed

A 64-token prompt: the chat prompt below, twice, cut to 64 tokens. Wall time per pass, warm passes only (the first
pass also pages the weights in). Builds ran interleaved; load averages were 14–45. Raw data:
[`raw/passes.txt`](raw/passes.txt).

| Build | Pass | Per pass | Per token |
| --- | --- | ---: | ---: |
| main (#698) | 2 tokens | 23.5–26.2 ms | about 12 ms |
| this, f32 weight tiles, no k split | 16 tokens | 63.5–66.5 ms | about 4.0 ms |
| this, with k split | 16 tokens | 50.8–54.3 ms | about 3.3 ms |
| **this, half weight tiles, with k split (kept)** | 16 tokens | **38.0–41.1 ms** | **about 2.5 ms** |

For scale: llama.cpp b11669 `llama-bench -p 16,64 -n 0 -r 3` on the same GGUF, run in the same hour, gave pp16
380 tok/s (about 42 ms per 16 tokens) and pp64 661 tok/s.

Every build gave the same tokens.

Sizing, for this pass only (stubs that give wrong tokens, not kept): without the `mm` matvecs, a 16-token pass took
4.0–8.7 ms, so the matvecs are most of it. Tried and dropped: 128 rows per threadgroup (50–53 ms; its k split also
overflowed the partial region, which a new load-time guard now refuses).

The one-token decode path is unchanged.

For drafting this is still costly. A 16-token verify pass takes about 2.3 single-token passes (38 ms against 17 ms),
so a drafted block must yield more than that to pay off.

## Correctness

The gate ([`gate.txt`](gate.txt)) passes every row. The exact and even-prompt rows now run their prompt through the
multi-token pass. New rows:

- **mm-mutant.** The multi-token matvec multiplies rows 8–15 of each tile by rows 0–7's weights. The tokens change.
- **dnfn-mutant.** The multi-token conv1d reads only the current token. The tokens change.
- **multi-slot-mutant.** Decode reads the state slot as if the 5-token prompt pass had moved it five slots instead of
  one. The tokens change.
- **chat-prompt.** A 34-token chat prompt runs in passes of 16, 16 and 2, and gives the same 32 tokens as the same
  prompt run two tokens per pass. The prompt is a user turn asking how a bicycle stays upright, then the assistant
  header with an empty think block; its ids come from llama-tokenize b11669.

The slot-mutant row of the MTP decode now names its target exactly, because the old text also matched the new code.

Outside the gate: the 34-token chat prompt gave the same 64 tokens as main.

Correction: the MTP head's source in the [MTP record](../2026-11-16-qwen35-mtp/README.md) and in `run.sh` named the main
repository. Kapitsa's manifest lists the file as the MTP sidecar of `Qwen/Qwen3.5-4B` @ 851bf6e. The digest was always
the pinned one.
