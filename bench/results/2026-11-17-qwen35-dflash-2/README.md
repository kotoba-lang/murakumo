# Qwen3.5-4B DFlash drafts: a lighter cycle (2026-11-17)

Linear AWAI-3017 (under AWAI-2938). Follow-up to [the first DFlash record](../2026-11-17-qwen35-dflash/README.md),
where a cycle cost about 56 ms whatever the block size.

## What changed

- **A matvec for up to 8 tokens.** The multi-token matvec `mm` used to multiply 16 token columns even for a block
  of 8. It now has an 8-column instantiation (`mm8_<dtype>`). A pass of 3–8 tokens takes it: a verify block of 8,
  and the short prompt passes. Half the multiplies, half the activation tile.
- **The gate's DFlash rows use blocks of 8.**

## Where a cycle goes

Block 8, think prompt. Raw data: [`raw/rounds.txt`](raw/rounds.txt).

- A cycle took 52–53 ms before and 39–40 ms now.
- Of the 39–40 ms, the verify pass is about 30 ms: about 25 ms for the main model's eight rows and 4–5 ms for their
  head over the 248,320-row embedding.
- The drafter is about 7 ms, about 3–4 ms of it for its own head.
- Replay and the context update are about 2 ms.

These were sized with stubs that drop one part and give wrong tokens; they are not kept.

## Speed

Builds ran interleaved: plain, DFlash with blocks of 8 and 16, on the two prompts of the first record. Every build
gave the same tokens as plain decode.

**Prompt opening a think block** (30 tokens), 256 tokens:

| Build | ms/token | Cycles / accepted drafts |
| --- | ---: | ---: |
| plain | 17.0–20.7 | — |
| **DFlash, block 8** | **12.3–12.4** | 79 / 179 |
| DFlash, block 16 | 16.5–20.6 | 75 / 184 |

Block 8 is about 1.4× faster than plain decode here. Block 16 still runs the 16-column matvec, so it gained nothing.

**Chat prompt with an empty think block** (34 tokens), 128 tokens:

| Build | ms/token | Cycles / accepted drafts |
| --- | ---: | ---: |
| plain | 16.8–17.0 | — |
| DFlash, block 8 | 18.2–18.7 | 59 / 68 |

At 2.15 tokens per cycle, drafting is still slower than plain decode here: 18.2–18.7 ms/token against 16.8–17.0,
though less so than the 23.9–24.6 ms/token of the first DFlash record.

The host was busy (load averages 8–15).

## Correctness

The gate ([`gate.txt`](gate.txt)) passes every row:

- **Short prompt.** The exact and even-prompt rows run their 5- and 6-token prompts through the 8-column kernel.
- **mm-mutant.** It now names the new template's tile multiply, so it covers both instantiations.
- **DFlash rows.** dflash-exact, the accept, replay and ctx mutants, and dflash-digest all run with blocks of 8.
