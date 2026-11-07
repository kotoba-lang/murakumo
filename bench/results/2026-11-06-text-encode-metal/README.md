# Prompt text in, CLIP hidden states out, in one Kotoba program on the Apple GPU (I3b, third slice, JUN-927)

Recorded 2026-11-06T17:29:33+0900 on 21mbp.local, Apple M1 Max, load average 8.60 10.01 11.10 (busy host: timing is diagnostic).

- amu: `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, `bin/amu` sha256 `3839d2616ba737f59835cb17bb58d79543c99098da73ffe17a67a7039427dd7c`, profile `amu-program-metal` (ADR 0405)
- oracle: Python 3.11.13, torch 2.6.0, transformers 5.4.0 `CLIPTextModel` and `CLIPTokenizer` (local oracles, not routes); tokenizer files from the ComfyUI v0.44.0 tarball of [2026-11-06-comfyui-oracle](../2026-11-06-comfyui-oracle/README.md)
- command: `sh bench/kernels/text-encode-metal/run.sh <amu checkout> <comfy>/comfy/sd1_tokenizer`

```
ids equal the Hugging Face CLIPTokenizer's (77 ids, BOS ... EOS padded)
hidden states: max_err=2.372e-03 pooled_err=1.391e-03 (limit 0.01) ok best=2.02 ms
mutant [rank]: the ids differ (detected)
mutant [causal]: error 3.049e+00 (detected)
gate passed
```

## What this shows
- The CLIP tokenizer ([2026-11-06-clip-tokenizer-metal](../2026-11-06-clip-tokenizer-metal/README.md)) and the CLIP text tower ([2026-11-06-clip-tower-metal](../2026-11-06-clip-tower-metal/README.md)) joined in **one Kotoba program** built by `bin/amu`: it reads ComfyUI's `vocab.json` and `merges.txt`, tokenizes a prompt line ("a photo of a cat sitting on a red sofa, highly detailed, 8k": commas, digits and a letter after a digit), builds BOS + tokens + EOS padded with EOS to 77, reads the Hugging Face safetensors weights, and runs the tower on the GPU. This is the first time a path from prompt text to model output runs end to end in Kotoba.
- The 77 ids equal Hugging Face's `CLIPTokenizer(prompt, padding="max_length", max_length=77)`. The last hidden states are within **2.4e-3** of PyTorch's float32 `CLIPTextModel` on the same random weights (mean 4.4e-4), the pooled vector within 1.4e-3, matching the tower-only run, so the joined tokenizer changes nothing numerically.
- Two mutants are detected: the merge rank order reversed (the ids differ), the causal mask off (error 3.0). The GPU part takes about 1.7 ms (best of 20, M1 Max under load); the tokenizer's table build is a one-time cost of a few tenths of a second.

## What it does not show
- Still the small random-weight tower (2 layers, width 128), ASCII prompts only, no prompt weighting, no chunking of prompts over 75 tokens, and no SDXL specifics (CLIP-L and CLIP-G together, the penultimate layer, the projection, pooled `y`). The program's source is the two earlier programs' text joined, with the ids file replaced by the tokenizer's output; nothing is shared as a module because the import route is still open.
- One prompt in the gate (the tokenizer's own gate has 21). Python, torch and transformers are local oracles only; the Metal profile is a platform route, not the browser-consent operation of the client-runtime contract; M1 Max; not a CI step.
