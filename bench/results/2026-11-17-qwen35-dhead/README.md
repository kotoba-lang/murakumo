# Qwen3.5-4B DFlash: the drafter's head over part of the vocabulary (2026-11-17)

Linear AWAI-3036 (under AWAI-2938). Follow-up to [the verify-pass record](../2026-11-17-qwen35-verify/README.md).

## What changed

The drafter's drafts came from a head over all 248,320 rows of the tied embedding: an 8-column `mm` over a 521 MB
Q6_K table every cycle (about 3–4 ms).

It now covers:

- tokens 0–65535 (byte-pair ids, the earliest merges first);
- the special tokens 248044–248079 (`<|im_end|>`, `<think>`, `</think>` and their neighbours).

That is about 26 % of the table. `argmax` takes the two ranges as one list and maps an index past the first range
back to its token id.

The verify pass still takes its argmax over the whole vocabulary, and only its argmaxes are emitted. A token outside
the range can never be drafted, but the output does not change.

## Speed

Blocks of 8, builds interleaved. Raw data: [`raw/rounds.txt`](raw/rounds.txt).

- Rounds 1–3 ran under load averages up to 33. They also compared a 32768-token range.
- Rounds 4–7 ran at load averages 8–14. They compared only the whole head with the 65536-token one.

Every build gave the same tokens as plain decode.

**Prompt opening a think block** (30 tokens), 256 tokens:

| Build | ms/token, rounds 4–7 | Cycles / accepted drafts |
| --- | ---: | ---: |
| plain (rounds 1–3) | 18.4–20.0 | — |
| head over the whole vocabulary (#702) | 12.2–15.6 | 79 / 179 |
| **head over 65536 + 36 tokens** | **11.4–14.8 (11.4 in three of four)** | 80 / 178 |
| head over 32768 + 36 tokens (rounds 1–3) | 12.3–15.8 | 88 / 167 |

**Chat prompt with an empty think block** (34 tokens), 128 tokens:

| Build | ms/token, rounds 4–7 | Cycles / accepted drafts |
| --- | ---: | ---: |
| plain (rounds 1–3) | 16.8–17.2 | — |
| head over the whole vocabulary | 17.8–18.0 | 59 / 68 |
| **head over 65536 + 36 tokens** | **16.8–17.1 (one round 19.8)** | 60 / 67 |

The 65536-token range keeps the acceptance and saves about 1 ms per token. Drafting on the empty-think chat text is
now level with plain decode instead of slower. The 32768-token range loses drafts (167 accepted against 179) and
was not kept.

## Correctness

The gate ([`gate.txt`](gate.txt)) passes every row. It was built with kotoba-lang/amu @ d121f176c, because the
earlier amu worktree had been removed from this machine meanwhile. The timing builds used the earlier amu. The dflash-exact row checks that the drafted decode with the
reduced head gives plain decode's 48 tokens. Its mutants still behave as before.
