# SDXL's two text towers at full size on the Apple GPU: CLIP-L and OpenCLIP-bigG (stage M slice 15, JUN-927)

Recorded 2026-11-06T18:28:50+0900 on 21mbp.local, M1 Max. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, profile `amu-program-metal`. Command: `sh bench/kernels/clip-full-metal/run.sh <amu> <sd1_tokenizer dir> <python3.11 with torch, transformers, safetensors, numpy>` (python 3.11, transformers 5.4.0, torch 2.6.0, CPU float32).

## What it does
The earlier tower program (2 layers, width 128) became parametric (layers, width, heads of 64, MLP width, activation) and gained the three outputs SDXL needs: the last hidden state (after the final LayerNorm), the **penultimate** hidden state (the output of layer NL-1, before the final LayerNorm: what SDXL's UNet cross-attends to for both towers), and the end-token embedding after final LayerNorm and `text_projection` (the pooled vector of the bigG tower). Large files are read in 1 GiB chunks (a single `pread` over 2 GiB fails). Weights are random Hugging Face `CLIPTextModelWithProjection` tensors written by make_fixture.py; the oracle is transformers' own PyTorch model, float32 on CPU, with `output_hidden_states`.

## Result (limits set before the L and G runs: 2e-2 for tiny and L, 5e-2 for G; |gpu - ref| / max(1, |ref|))
```
tiny (6945536 parameters): last=2.322e-03 pen=1.634e-03 proj=1.120e-03 finite=1 (limit 0.02) ok best=1.7 ms
L (123650304 parameters): last=2.656e-03 pen=4.776e-03 proj=1.451e-03 finite=1 (limit 0.02) ok best=12.3 ms
G (694659840 parameters): last=3.431e-03 pen=1.005e-02 proj=1.753e-03 finite=1 (limit 0.05) ok best=69.7 ms
mutant [causal]: worst error 3.301e+00 (detected)
mutant [penultimate]: worst error 1.688e+00 (detected)
mutant [activation-dropped]: worst error 2.436e+00 (detected)
mutant [projection]: worst error 1.682e+00 (detected)
gate passed
```
- **L** is the OpenAI ViT-L/14 text tower (12 layers, 768, 12 heads, MLP 3,072, quick_gelu): 12.5 ms for all three outputs. **G** is the OpenCLIP bigG text tower (32 layers, 1,280, 20 heads, MLP 5,120, gelu): 69 ms. Each is a few tens of milliseconds against seconds for one UNet call, so text encoding is not a cost worth tuning.
- The larger error at G's penultimate output (1.0e-2) is the f16 accumulation over 31 layers on a residual stream that grows to ~14; relative to the largest value it is about 7e-4.

## What it does not show
- **Random weights**: no real CLIP checkpoint was read, so nothing is said about the trained models' value ranges (trained CLIP towers can have outlier channels that stress f16 harder than this fixture). The Hugging Face tensor names are used; the OpenCLIP naming of SDXL's single-file checkpoint (`conditioner.embedders.1.model.*`, fused `in_proj` attention weights) would need a name/shape mapping that is not written.
- The tokenizer is the earlier slice's (BPE of CLIP-L); bigG shares the same vocabulary but ComfyUI pads it differently (with 0 instead of the end token for the G tower); that padding rule is not exercised here, and the ids are the oracle's.
- SDXL concatenates L's and G's penultimate states along the channel axis (768 + 1280 = 2,048) and builds the 2,816-wide label embedding from the pooled vector plus size/crop conditioning; neither concatenation nor the Fourier embeddings of the conditioning are done here.
- ComfyUI's SDXL CLIP-L applies the clip skip to its `hidden` layer -2 in the same way (checked against the transformers `hidden_states[-2]` here, not against ComfyUI's SDXLClipModel code).
- Tiny-model mutants (causal mask off, penultimate one layer late, activation dropped, projection read from the wrong buffer) are detected; a quick_gelu to gelu swap moves the tiny model by only 4.3e-2 (above the 2e-2 gate limit, below the 5e-2 mutant bar).
