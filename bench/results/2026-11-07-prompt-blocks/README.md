# Prompts longer than 75 tokens: blocks of 77 (stage M slice 45, JUN-927)

Recorded 2026-11-07T03:39:13+0900, M1 Max, load average 6.34 6.54 6.78. Programs: `bench/kernels/clip-tokenizer-metal/` (standalone, `BLOCKS=1`) and `bench/kernels/pipeline-metal/pipeline_metal.kotoba.in`; oracle: ComfyUI v0.44.0's own `SDTokenizer.tokenize_with_weights` (the batching into blocks) and the Hugging Face towers.

Both programs used to truncate a prompt at 75 tokens. ComfyUI instead cuts it into blocks of 77 (start, up to 75 tokens, end, padding), runs each block through the text towers on its own and joins the outputs along the sequence axis, so the UNet's cross-attention sees 77 x n keys; the pooled vector is the first block's.

What was ported:
- The batching rule exactly: a token group (one per weighted segment) of fewer than 8 tokens is kept whole (the block is closed with the end token and padding and a new one started when it does not fit); a group of 8 or more is cut where the block is full. Padding is the end token for CLIP-L and 0 for the bigG tower.
- In the render program: the towers are recorded once and run once per block (id copy, towers, output copy at the block's row offset), the weights are applied per block, and the conditional and unconditional contexts are repeated up to the least common multiple of their block counts (ComfyUI does this when it batches them; attention over a repeated sequence gives the same result). `cat_ctx` takes the repeat; the cross-attention key count and the K / V row count are runtime values (S[60], S[61] since slice 48; slice 45 used 56 and 57, which the 47th kernel slot then overlapped) instead of 77 and 128. Up to 4 blocks (300 tokens) per prompt; more is refused.

| check | result |
|---|---|
| standalone, 32 blocks from 20 long prompts (60 to 150 words, long weighted segments, groups of exactly 7 and 8 tokens at a block boundary, 60 weighted words, empty and short prompts) against ComfyUI | every id exact, every weight within 8/65536 |
| 3 batching mutants (the 8-token threshold, the room per block, keeping a small group whole) | all detected |
| render program, hf and single-file layouts, conditional prompt 2 blocks, unconditional 3 (so the context is repeated to 6 x 77 = 462 keys), weighted, non-ASCII | gate passed in both: ids exact, ctx 9.4e-3 / 9.4e-3 (hf), 6.4e-3 / 7.0e-3 (single), steps within 3.7e-2, image within the gate |
| 3 render-program mutants (no repeat, the same block ids for every block, the same output row for every block) | detected (worst error 5.0, 5.1, 4.9) |

Also changed along the way: the piece, segment and nesting caps of the weighting parser were raised (300 pieces, 600 segments) because a prompt with 60 weighted words already needs more than 120 pieces.

Process note: a gate run failed silently once because earlier failed runs had left about 40 GB of fixture weights in the temporary directory and the disk was full; I deleted those scratch directories (they are this session's `pipeline-metal.*`, `clip-tok.*` and `big-f16.*`). The gates remove their weights on success and keep them on failure, so repeated failures fill the disk.

Limits: more than 4 blocks refused; `embedding:` names not implemented; the block count and the repeat are decided at run time, so a different prompt does not need a different build (the placeholders still name the prompt files).
