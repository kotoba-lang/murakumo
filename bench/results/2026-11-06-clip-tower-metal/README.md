# A CLIP text tower on the Apple GPU from Hugging Face safetensors weights (I3b, second slice, JUN-927)

Recorded 2026-11-06T17:26:50+0900 on 21mbp.local, Apple M1 Max, load average 10.05 10.76 11.55 (busy host: timing is diagnostic).

- amu: `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, `bin/amu` sha256 `3839d2616ba737f59835cb17bb58d79543c99098da73ffe17a67a7039427dd7c`, profile `amu-program-metal` (ADR 0405)
- oracle: Python 3.11.13, torch 2.6.0, transformers 5.4.0 `CLIPTextModel` and `CLIPTokenizer` (a local oracle, not a route); tokenizer files from the ComfyUI v0.44.0 tarball of [2026-11-06-comfyui-oracle](../2026-11-06-comfyui-oracle/README.md)
- command: `sh bench/kernels/clip-tower-metal/run.sh <amu checkout> <comfy>/comfy/sd1_tokenizer`

```
CLIP tower 2 layers, hidden 128, 2 heads, 77 tokens: max_err=2.329e-03 pooled_err=1.900e-03 (limit 0.01) ok best=1.01 ms
mutant [causal]: error 3.151e+00 (detected)
mutant [activation]: error 2.411e+00 (detected)
mutant [transpose]: error 3.848e+00 (detected)
mutant [residual]: error 3.624e+00 (detected)
gate passed
```

## What this shows
- A CLIP text tower runs on the GPU from one Kotoba program built by `bin/amu`, with its weights read from a safetensors file **in the Hugging Face layout** (names such as `text_model.encoder.layers.0.self_attn.q_proj.weight`, Linear weights stored [out, in]): the program indexes the header, admits every tensor (size = shape x dtype, offsets cover the data region), reads the data region into one Metal buffer, looks tensors up by name, converts the weight matrices float32 -> half on the GPU, and encodes **32 kernels into one command buffer and one encoder** (12 weight conversions, the embedding gather, then per layer LayerNorm, q/k/v, causal fused attention, out_proj + residual, LayerNorm, fc1 + quick_gelu, fc2 + residual, and the final LayerNorm). The linear kernel reads the [N, K] weight with a transposed `simdgroup_load`, so no weight is rearranged.
- The model is Hugging Face's `CLIPTextModel` with random weights (2 layers, hidden 128, 2 heads of 64, intermediate 512, quick_gelu, the real 49,408-token vocabulary and 77 positions), fed the real token ids of "a photo of a cat sitting on a red sofa" padded with the end token. Against the same model run in float32 on the CPU by PyTorch the GPU's last hidden states (77 x 128) are within **2.3e-3** at their worst element (mean 4.4e-4) and the pooled vector (the end-token row) within 1.9e-3, in |gpu - ref| / max(1, |ref|), outputs up to 3.6.
- Four mutants are detected (error > 0.05): the causal mask off (3.2), quick_gelu off (2.4), the weights read untransposed (3.8), the first residual dropped (3.6). The 32 dispatches take about 1.0 ms in all (best of 20, host load average 17 to 20, M1 Max).

## What it does not show
- 2 layers at width 128, not CLIP-L (12 layers, 768) or CLIP-G (32 layers, 1280, plain GELU, a text projection); random weights, not a trained model. The limit (1e-2) is the f16 scale of the earlier slices, set before this slice's first run; the first run gave 2.3e-3.
- The tower takes its 77 ids from a file; the tokenizer of [2026-11-06-clip-tokenizer-metal](../2026-11-06-clip-tokenizer-metal/README.md) is a separate program, not yet joined to it. The `erf` GELU path (`ACT` 2, for CLIP-G) is in the kernel but not exercised here. SDXL's use of the penultimate layer's hidden state and of the text projection is not implemented.
- Rows beyond 77 (padding to 128 for the 64-row tile) are computed and ignored. The GPU is reached through the Metal profile, a platform route, not the browser-consent operation of the client-runtime contract. M1 Max only; the dump goes to stderr and is split by byte count. Not a CI step.
