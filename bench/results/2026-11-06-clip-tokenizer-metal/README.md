# The CLIP tokenizer in Kotoba on the Metal route (I3b, first slice, JUN-927)

Recorded 2026-11-06T17:22:06+0900 on 21mbp.local, Apple M1 Max.

- amu: `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, `bin/amu` sha256 `3839d2616ba737f59835cb17bb58d79543c99098da73ffe17a67a7039427dd7c`, profile `amu-program-metal` (ADR 0405)
- tokenizer files: ComfyUI v0.44.0 `comfy/sd1_tokenizer/vocab.json` (e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349) and `merges.txt` (9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a), from the tarball recorded in [2026-11-06-comfyui-oracle](../2026-11-06-comfyui-oracle/README.md)
- oracle: Python 3.11.13, transformers 5.4.0 `CLIPTokenizer` (the class ComfyUI's SDTokenizer loads from these files); a local oracle, not a route
- command: `sh bench/kernels/clip-tokenizer-metal/run.sh <amu checkout> <comfy>/comfy/sd1_tokenizer`

```
all 21 prompt lines equal the Hugging Face CLIPTokenizer (vocab 47071 merges 46881)
mutant [rank]: 17 of 21 lines differ (detected)
mutant [lowercase]: 3 of 21 lines differ (detected)
mutant [contraction]: 3 of 21 lines differ (detected)
time: 0.21 s for the whole run incl. table build (diagnostic)
gate passed
```

## What this shows
- The CLIP byte-pair tokenizer written in Kotoba and built by `bin/amu` reads ComfyUI's `vocab.json` and `merges.txt` (with libSystem's `open` and `pread`), builds a hash table of the vocabulary (47,071 of the 49,408 entries: the ones with ASCII keys) and one of the merge ranks (46,881 of 48,894 merges: the ASCII ones), and tokenizes the 21 lines of `prompts.txt` to the same ids as Hugging Face's `CLIPTokenizer`, line for line, with `<|startoftext|>` and `<|endoftext|>` and truncation to 77. The prompts cover mixed case, SD-style comma lists, contractions, a possessive, digits, brackets and other symbols, quotes, runs of punctuation, repeated and trailing spaces, an empty line, two very long words, a single character, the apostrophe cases and a prompt of more than 75 tokens.
- "a photo of a cat" gives 49406 320 1125 539 320 2368 49407, the ids the CLIP papers' examples show.
- Three mutants are detected: the merge rank order reversed (17 of 21 lines differ), letters not lowercased (3), the contraction rule dropped (3). The whole run, tables included, takes 0.38 s.

## What it does not show
- ASCII only: non-ASCII prompts are refused, as are the special-token spellings and HTML entities. The 2,337 vocabulary entries and 2,013 merges with non-ASCII bytes are skipped, so a non-ASCII prompt could not be tokenized correctly anyway. `ftfy`'s text fixing (mojibake, curly quotes) is not reproduced.
- ComfyUI's own prompt handling on top of the tokenizer is not implemented: weight syntax `(word:1.2)`, embeddings, chunking of prompts longer than 75 tokens into several 77-token chunks, and the SDXL `clip_g` tokenizer's different padding. The comparison is against `CLIPTokenizer` itself, with `truncation=True, max_length=77`.
- 21 prompts, not a corpus. The vocabulary and merge files are the SD1 tokenizer's; that SDXL's CLIP-L and CLIP-G share them (CLIP-G pads with 0 rather than the end token) is ComfyUI's configuration, not checked here. The Metal profile is a platform route, not the browser-consent operation of the client-runtime contract. Not a CI step.
