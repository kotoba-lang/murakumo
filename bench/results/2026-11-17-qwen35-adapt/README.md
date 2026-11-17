# Qwen3.5-4B DFlash: adapting the block size (2026-11-17, not merged)

Linear AWAI-3054 (under AWAI-2938). Follow-up to [the tree record](../2026-11-17-qwen35-tree/README.md), which found
blocks of 4 better on text where few drafts are accepted and blocks of 8 better elsewhere. This experiment picked
each cycle's block from the previous cycle's accepted drafts. It produced no gain, so the code was not merged. It is
kept on branch `qwen35-adapt` (commit b7e36f63, three lines).

## Result

Interleaved rounds, load averages 8–14. Raw data: [`raw/rounds.txt`](raw/rounds.txt). Every build gave the same tokens
as plain decode.

| Build | chat with an empty think block, 128 tokens | think block, 256 tokens |
| --- | ---: | ---: |
| plain | 16.8–17.0 | — |
| fixed blocks of 4 | 16.1–19.4 (60 / 67) | 13.6–15.8 (99 / 157) |
| fixed blocks of 8 | 16.9–20.7 (60 / 67) | **11.4–12.9** (80 / 178) |
| adaptive: 8 after a cycle that accepted 3 or more drafts, else 4 | 16.3–16.8 (60 / 67) | 12.6–16.4 (91 / 169) |
| adaptive: 8 after 2 or more, else 4 | 16.6–16.8 (61 / 66) | 12.0–16.7 (86 / 172) |

ms/token; in parentheses, cycles / accepted drafts.

## Why the block of 8 stays

- **A short block costs more than it saves.** A cycle with a block of 4 costs only about 1.5 ms less than one with
  a block of 8 (the verify pass costs about the same for 3–8 tokens). It also caps the next cycle at 4 tokens.
- **Falling back loses long runs.** After a short accepted run, the adaptive rules drop to 4 and miss the long runs
  that follow on the think text: 169–172 drafts accepted against 178.
- **The gain on the chat text is small.** It is about 0.5 ms/token.

Blocks of 8 remain the default (the gate's DFlash rows use 8). A gain on low-acceptance text needs a cheaper cycle
or a better draft, not a smaller block.
