# Qwen3.5-4B decode with MTP drafts, first slice (2026-11-16)

Linear AWAI-2938 (E5 in [docs/engine-plan.md](../../../docs/engine-plan.md)). Follows
[the fused DeltaNet pass](../2026-11-16-qwen35-decode-4/README.md).

## What this adds

- **A pass of two tokens.** The matvec templates take N tokens and read the weights once:
  - `mv2_q8_0`, `mv2_q6_k`, `mv2_bf16` keep both tokens' sums per lane;
  - `mvk2_q4_k`, `mvk2_q5_k` load 8 bytes per lane, so both tokens' activations fit in registers.

  The other kernels run once per token. The Gated DeltaNet states (conv and delta rule) have three slots: a pass of
  n tokens reads slot c and writes c + 1 .. c + n. The state after each token of the pass survives it, and a
  rejected draft never overwrites the state of the token before it. The plain decode now runs the prompt two tokens
  per pass.
- **The MTP head as drafter.** Qwen3.5-4B ships one MTP layer:
  - an fc over [embedding | final-norm hidden], one gated attention layer with its own KV cache, and an MLP;
  - it uses the tied head.

  The GGUF does not include it. It is read from `mtp.safetensors`:

  | | |
  | --- | --- |
  | Source | MTP sidecar of Qwen/Qwen3.5-4B @ 851bf6e (Kapitsa's manifest lists it as a companion of nace-ai/Qwen3.5-4B-MLX @ 9f7d3278; corrected 2026-11-17, this row first named the main repository) |
  | Format | BF16, norms stored as 1 + w |
  | sha256 | 4f491dd27975520ec9c9bfa970ca215cc7f12a0328761d3656ab7b9f388e472a |
  | Provenance | the same file Kapitsa 0.2.36 downloaded for [E0](../2026-11-15-e0-kapitsa/README.md); its manifest records this digest. No upstream digest is checked here. |

  The program checks the pinned digest before any other read and parses the safetensors header strictly. It converts
  the matrices to Q8_0 on the GPU once (`bfq8`), which halves the bytes the head reads per draft.
- **One command buffer per cycle.** The buffer first runs the MTP head over the rows the previous verify accepted,
  which drafts one token into an arena word. Then it verifies [last token, draft] in one two-token pass.
  - If the first argmax equals the draft, both argmaxes are emitted and the states advance two slots.
  - Otherwise only the first argmax is emitted and the states advance one slot.

  Both possible buffers of the next cycle are encoded while the current one runs. The one that does not apply is
  released without being committed.

Every emitted token is the main model's argmax, so the output equals the plain greedy decode's. The program is
`bench/kernels/qwen35-metal/qwen35_spec.kotoba.in`. The plain program is unchanged apart from the two-token prompt.

## Speed (ms/token, interleaved builds; [`raw/compare.txt`](raw/compare.txt), load averages 8–14)

Prompt `760 6511 314 9338 369`, 64 decode steps:

| Round | #696 (main) | plain, this branch | MTP drafts (accepted / cycles) |
| --- | ---: | ---: | ---: |
| 1 | 17.14 | 17.11 | 15.30 (29/36) |
| 2 | 17.35 | 17.06 | 15.33 (29/36) |
| 3 | 17.12 | 17.14 | 15.22 (29/36) |

Chat prompt ([`raw/prompt-chat.txt`](raw/prompt-chat.txt): a user turn asking how a bicycle stays upright, then
the assistant header with an empty think block; 34 tokens from llama-tokenize b11669), 256 decode steps:

| Round | plain | MTP drafts (accepted / cycles) |
| --- | ---: | ---: |
| 1 | 23.76 | 30.78 (107/150) |
| 2 | 25.12 | 25.27 (107/150) |
| 3 | 35.54 | 34.67 (107/150) |

On the short prompt's repetitive continuation, 81 % of drafts are accepted and the drafted decode is about 11 %
faster. On the chat text, 71 % are accepted and it is no faster: a cycle costs about 1.7 plain tokens, and it
yields 1.71 tokens. The host noise in the chat rounds (24–36 ms for the same binary) is larger than any difference.

Where a cycle's time goes (synced probes, the same minutes):

- the two-token pass is about 1.34× a one-token pass (24.2 against 18.0 ms);
- the draft adds the MTP layer (about 128 MB of Q8_0) and one more read of the tied head (521 MB of Q6_K).

These two costs are what the next slices cut. Kapitsa reaches 78–80 tok/s with the MTP head and 106–110 with DFlash2
on this Mac.

## Tried and rejected

| Variant | Result |
| --- | --- |
| two-token Q4_K/Q5_K matvec keeping both tokens' 32 activations per lane in registers (16-byte loads) | 123–139 ms per two-token pass (register spill) |
| the same with the activations re-read per row | 138 ms |
| token-split simdgroups (each simdgroup the one-token kernel for one token, two simdgroups on the same rows; bit-exact with the one-token kernel) | 20.0 ms/token drafted, against 15.5 for the 8-byte kernel |

## Correctness

The gate ([`gate.txt`](gate.txt)) passes every row. New rows:

- **even-prompt.** Plain decode with a 6-token prompt continues with the next 15 exact tokens. This caught a bug in
  the two-token prompt: after a prompt whose last pass took two tokens, the first decode step read the wrong argmax
  word.
- **spec-exact.** The drafted decode's 48 tokens equal the plain decode's.
- **accept-mutant.** Accepting every draft without the check changes the tokens.
- **slot-mutant.** A rejected draft advancing two state slots changes the tokens.
- **mtp-mutant.** Breaking the MTP head leaves the tokens equal while accepted drafts fall to 0.
- **mtp-digest.** An MTP digest off by one character is refused.

Over 256 tokens, both prompts give identical drafted and plain outputs (hashes in `raw/compare.txt`).
