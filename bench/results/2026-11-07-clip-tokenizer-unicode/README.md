# CLIP tokenizer on non-ASCII prompts (stage M slice 41, JUN-927)

Recorded 2026-11-07T00:45:25+0900, M1 Max, load average 11.93 12.51 12.15. Programs: `bench/kernels/clip-tokenizer-metal/` (standalone) and the tokenizer inside `bench/kernels/pipeline-metal/pipeline_metal.kotoba.in`; oracle: Hugging Face `CLIPTokenizer` (transformers 5.4.0, the files ComfyUI ships in `comfy/sd1_tokenizer`).

The Kotoba tokenizer used to refuse any non-ASCII byte, so a Japanese prompt could not be rendered at all. It now decodes UTF-8, lower-cases, classifies each code point (whitespace, letter, number, other) the way the oracle's pre-tokenizer does, and runs byte-level BPE over the UTF-8 bytes mapped through GPT-2's `bytes_to_unicode`. The vocabulary and merge loaders previously dropped every entry containing a non-ASCII byte (vocab 47,071 of 49,408, merges 46,881 of 48,894); they now load all of them.

| check | result |
|---|---|
| original 21 ASCII lines + 6 Japanese / accented / fullwidth / emoji lines | all 27 equal the oracle |
| `prompts_unicode.txt`: 17,734 lines ("a<c>b" for every code point of the small ranges, every third of the CJK / Hangul / emoji ranges, both ends of each range, plus mixed lines) | all equal the oracle |
| 9 mutants (merge rank, ASCII lower, contraction, CJK class, 3-byte UTF-8, Latin-1 lower, ideographic space, byte map, fullwidth lower) | all detected (1 to 7,891 lines differ) |
| 6 refusals (Cyrillic, a decomposed accent, a titlecase letter, invalid UTF-8, truncated UTF-8, U+2329) | all refused with a message, no ids |
| render program, hf and single-file layouts, conditional prompt a mix of Japanese, ASCII, accents, fullwidth and emoji | ids exact (0 mismatches), conditioning 8.3e-3 / 5.9e-3, image 1.7e-2 against ComfyUI; the digest, swap and write checks of slices 37 to 40 unchanged |

What it supports: ASCII, Latin-1, Latin Extended-A (except U+0130), common symbols, emoji, CJK ideographs, kana, Hangul, fullwidth and halfwidth forms, and the whitespace of the Unicode White_Space property. Anything else is refused, not tokenized differently.

Found by the oracle, not assumed: the transformers 5 tokenizer NFC-normalizes (a letter plus a combining accent becomes one token), and its whitespace is the White_Space property, not `str.isspace` (U+001C to U+001F are ordinary characters). NFC is not implemented, so every character it could change is refused: combining marks U+0300 to U+036F and U+3099 / U+309A (these matter, since macOS file names and some input methods produce decomposed Japanese), and U+2329, U+232A, U+2ADC. U+2E2F is a letter, which the corpus caught.

Not done: Greek, Cyrillic, Arabic, Indic and other scripts (refused); NFC for decomposed text (refused, so a prompt pasted from a decomposed source fails loudly and has to be normalized first); ComfyUI's weighting syntax `(text:1.2)`, chunking past 75 tokens, embeddings and HTML entities; the separate `text-encode-metal` program still has the ASCII-only copy of the tokenizer. The supported-range table was checked against the oracle over the corpus, not against Unicode's database, so a code point inside a supported range that the corpus skipped (the CJK ranges are sampled) is covered only by the range rule.
