# From prompt text to image in one GPU program (stage M slice 22, JUN-927)

Recorded 2026-11-06T19:35:11+0900 on 21mbp.local, M1 Max, load average 12.82 12.92 16.76. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, profile `amu-program-metal`. Command: `sh bench/kernels/pipeline-metal/run.sh <amu> <ComfyUI-0.44.0> <stubs> <python with torch, transformers, safetensors, einops, numpy, tqdm> <sd1_tokenizer dir>` (the same gate as slices 18 to 21, extended).

## What changed
The render program of slice 21 still read the 77 token ids of its two prompts from files made by Hugging Face's tokenizer. The Kotoba CLIP tokenizer of an earlier slice (vocab.json and merges.txt loaded into hash tables, GPT-2-style pre-tokenization, byte-pair merges) is now inside it: the program reads the **prompt text** (a file holding `a photo of a cat sitting on a red sofa`, and one holding the empty prompt), tokenizes it twice per prompt (BOS, tokens, EOS, then the end token as padding for CLIP-L and **0** after the first end token for bigG, ComfyUI's `pad_with_end=False` rule), runs both towers, builds the conditioning and renders. Text in, 256 x 256 image out, in one process.

## Result (limits as in slices 18 to 21; the token ids must match exactly)
```
4 Euler steps (2 UNet passes each) + VAE decode, 256 px, 2886 recorded dispatches: ids_mismatch=0.000e+00 ctx_c=8.855e-03 y_c=2.563e-03 ctx_u=1.003e-02 y_u=2.642e-03 step0=3.545e-02 step1=3.636e-02 step2=3.613e-02 step3=3.640e-02 image=1.688e-02  finite=1  ref|x_final|max=62.44 ref|image|max=4.13 ok process wall 2 s
mutant [guidance]: worst error 4.924 (detected)
mutant [timestep-reversed]: worst error 2.549 (detected)
mutant [uncond-context]: worst error 4.705 (detected)
mutant [concat-order]: worst error 11.46 (detected)
mutant [size-order]: worst error 2.144 (detected)
mutant [g-padding]: worst error 2.245 (detected)
mutant [vae-residual]: worst error 8.857 (detected)
gate passed
```
- **ids_mismatch=0**: all four id vectors (L and G, prompt and empty prompt, 77 each) equal Hugging Face's `CLIPTokenizer` output with ComfyUI's padding rule. Everything downstream is unchanged from slice 21 (conditioning within 1.0e-2, steps within 3.6e-2, image within 1.7e-2 of ComfyUI), as it must be if the ids are identical.
- Seven mutants are detected, one of them new: the bigG padding rule shifted by one position (the ids differ).
- The program's main function stays under the let-binding limit found in slice 21 (the tokenizer lives in two helper functions).

## What it does not show
- The tokenizer is the earlier slice's: ASCII prompts only (it refuses a non-ASCII byte), no prompt weighting like `(word:1.2)`, no embeddings, no BREAK; one short prompt and the empty prompt were tested here (the earlier tokenizer slice gated one prompt against Hugging Face as well), so unusual inputs (digits, punctuation, long prompts over 75 tokens) are not covered by this gate.
- Random tower, UNet and VAE weights, small UNet and VAE (model_channels 64, ch 32), 256 px, Euler only. No trained checkpoint (D1), no decision where a real render runs (D4).
