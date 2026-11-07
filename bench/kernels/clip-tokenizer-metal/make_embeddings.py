#!/usr/bin/env python3
"""Write the test embeddings (textual-inversion files in the layout ComfyUI reads for SDXL: .safetensors with clip_l and clip_g tensors) into a directory:
bad (2 rows, F32), one (1 row, F32), half (2 rows, F16), sub/tri (3 rows), and a 1-D one (a single vector); deterministic.
  python3 make_embeddings.py <dir>"""
import os, sys, torch
from safetensors.torch import save_file
d = sys.argv[1]; os.makedirs(d + "/sub", exist_ok=True)
g = torch.Generator().manual_seed(11)
def mk(path, n, dt=torch.float32, one_d=False):
    l = torch.randn(n, 768, generator=g) * 0.02; gg = torch.randn(n, 1280, generator=g) * 0.02
    if one_d: l, gg = l[0], gg[0]
    save_file({"clip_l": l.to(dt).contiguous(), "clip_g": gg.to(dt).contiguous()}, path)
mk(d + "/bad.safetensors", 2); mk(d + "/one.safetensors", 1); mk(d + "/half.safetensors", 2, torch.float16)
mk(d + "/sub/tri.safetensors", 3); mk(d + "/vec.safetensors", 1, one_d=True)
mk(d + "/../escape.safetensors", 1)          # outside the directory: "embedding:../escape" must not load it
