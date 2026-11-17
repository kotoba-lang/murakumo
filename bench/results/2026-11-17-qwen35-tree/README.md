# Qwen3.5-4B DFlash: a two-branch tree verify (2026-11-17, not merged)

Linear AWAI-3050 (under AWAI-2938). An experiment toward tree verification for text where few drafts are accepted.
It produced no gain, so the code was not merged. It is kept on branch `qwen35-tree` (commit c8affd92) for reference.

## What was tried

The verify pass costs about the same for 3–8 tokens ([verify-pass record](../2026-11-17-qwen35-verify/README.md)),
so one 8-token pass checked two branches of 4:

- branch 0: [last token, d1, d2, d3];
- branch 1: [last token, d1′, d2, d3], where d1′ is the drafter's runner-up for position 1 (`argtop2`). DFlash
  drafts every position in parallel, so d2 and d3 do not depend on d1.

The drafter ran a block of 4. In the main model:

- each branch got its own attention slots, with RoPE at the shared positions, and each row attends to the shared
  context and its own branch only;
- the Gated DeltaNet restarted from the read state at each branch.

The longer accepted branch won. When branch 1 won, its attention entries were copied to the shared slots, its
delta-rule inputs were replayed, and its rows went into the drafter's context.

## Result

Interleaved rounds. The table shows the cleanest round of each set. Raw data: [`raw/rounds.txt`](raw/rounds.txt).
Every build gave the same tokens as plain decode, so the branch-1 path is lossless too.

| Prompt | plain | chain, block 8 | chain, block 4 | tree |
| --- | ---: | ---: | ---: | ---: |
| chat with an empty think block, 128 tokens | 16.8 | 16.7–17.3 (60 / 67) | 16.1–16.4 (60 / 67) | 16.1–16.2 (58 / 69) |
| think block, 256 tokens | — | 11.3–11.4 (80 / 178) | 13.5–13.6 (99 / 157) | 13.5–13.6 (96 / 160) |

ms/token; in parentheses, cycles / accepted drafts.

- On the chat text, the runner-up branch added 2 accepted drafts in 128 tokens (69 against 67). The tree's speed
  there comes from the smaller drafter block: a chain with blocks of 4 is as fast.
- On the think text, a tree of two branches of 4 caps a cycle at 4 tokens. It is slower than the chain with blocks
  of 8.

The drafter's runner-up for the first position rarely turns out to be the main model's token. For DFlash, two
branches of 4 are worth less than one longer chain.

## What this suggests instead

- **Pick the block per text.** Blocks of 4 do better on low-acceptance text: 16.1–16.4 against 16.7–17.3 ms/token
  on the chat prompt. Blocks of 8 do better on high-acceptance text.
- **Adapt the block to recent acceptance.** For example, start the next cycle at 4 after a short accepted run and
  at 8 after a long one. This would let one decode handle both kinds of text.
