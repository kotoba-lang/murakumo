# Prompt weighting `(text:1.2)` (stage M slice 44, JUN-927)

Recorded 2026-11-07T02:32:02+0900, M1 Max, load average 9.83 10.20 10.84. Programs: `bench/kernels/clip-tokenizer-metal/` (standalone, `WEIGHTS=1`) and `bench/kernels/pipeline-metal/pipeline_metal.kotoba.in`; oracle: ComfyUI v0.44.0's own `SDTokenizer.tokenize_with_weights` and `ClipTokenWeightEncoder.encode_token_weights` formula, with the Hugging Face towers.

ComfyUI turns `(word:1.3)`, `(word)` (x1.1 per level), nesting and `\(` escapes into one weight per token, then scales each token's hidden state toward the empty prompt's: `z[j] = (z[j] - z_empty[j]) * w + z_empty[j]` for every position whose weight is not exactly 1. The render program did not read parentheses at all, so a prompt like `(masterpiece:1.4)` was tokenized as literal text.

What it does now:
- Parsing: a Kotoba port of `escape_important`, `parse_parentheses` and `token_weights` (explicit stack, the same quirks: the last colon decides the weight, anything after it that Python's `float()` rejects leaves the text and multiplies by 1.1, unbalanced parentheses, negative nesting). Weights are fixed point with 16 fractional bits. Each segment is tokenized on its own, as ComfyUI does (`cat(dog:1.2)fish` is three words). Weights written as `nan`, `inf` or with underscores, more than 12 digits, huge exponents and more than 80 pieces or 200 segments are refused.
- Blending: two Metal kernels (`blend_l`, `blend_g`) apply the formula to the conditional and unconditional L and G outputs. The empty prompt's two tower outputs (start, end, then the end token for L and 0 for G, as `gen_empty_tokens`) are computed once per render by re-running the towers on those ids. The pooled vector is not weighted, as in ComfyUI.

| check | result |
|---|---|
| standalone, 25 weighted prompts (nesting, escapes, bad numbers, unbalanced, signs, exponents, a weighted special token, Japanese) against ComfyUI's tokenizer | ids exact, every weight within 8/65536 |
| 4 weight mutants (paren factor, escape handling, explicit weight, segment boundary) | all detected |
| the older plain-tokenizer corpora (39 + 17,988 lines) with weights off | unchanged, all equal |
| render program, hf and single-file layouts, both prompts weighted (conditional: nested, explicit, escaped parenthesis, Japanese, fullwidth, emoji, decomposed text; unconditional: three weights) against ComfyUI's weighting formula on the Hugging Face towers | gate passed in both; ids exact, ctx 8.5e-3 / 8.6e-3 (hf) and 6.4e-3 / 7.0e-3 (single-file), image within the gate; the digest, write and swap checks unchanged |
| 3 render-program mutants (blend off for L, off for G, wrong scale) | detected (worst error 1.6, 1.9, 5.7) |

Limits: at this slice only prompts that fit one 77-token block (blocks came in slice 45); `embedding:` names are not implemented; weights above 1,000,000, the hex-float and non-ASCII-digit forms Python's `float()` accepts, and `nan`/`inf` are refused or treated as text. `main` of the render program was at the 256-locals limit, so the weighting bookkeeping lives in helpers (`alloc-w!`, `weights-rec!`, `run-weights!`, `tokenize2!`).
