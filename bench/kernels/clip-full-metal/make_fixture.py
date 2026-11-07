#!/usr/bin/env python3
"""Local test fixture and oracle for clip_full_metal.kotoba.in (not a gate, not a route).
  python3 make_fixture.py write DIR TOKDIR CONFIG   CONFIG is tiny | L | G
Writes DIR/model.safetensors (a Hugging Face CLIPTextModelWithProjection with RANDOM weights, float32, the layer/width/head/MLP
sizes of the named config: L = OpenAI CLIP ViT-L/14 text tower (12 layers, 768, 12 heads, 3072, quick_gelu), G = OpenCLIP bigG text
tower (32 layers, 1280, 20 heads, 5120, gelu), tiny = 3 layers of 128), DIR/ids.txt (77 ids of a prompt padded with the end token), and
the float32 oracle outputs computed on the CPU: expect_last.f32 (last_hidden_state, 77 x H), expect_pen.f32 (hidden_states[-2], the
layer SDXL conditions on, before the final LayerNorm), expect_proj.f32 (text_embeds at the end token, H floats).
Needs torch, transformers, safetensors, numpy (python3.11, transformers 5.4.0, torch 2.6.0 were used)."""
import sys, os
import numpy as np, torch
from transformers import CLIPTextConfig, CLIPTextModelWithProjection, CLIPTokenizer
CFG = {"tiny": (3, 128, 2, 512, "quick_gelu"), "L": (12, 768, 12, 3072, "quick_gelu"), "G": (32, 1280, 20, 5120, "gelu")}
def write(d, tokdir, name):
    nl, h, nh, inter, act = CFG[name]
    os.makedirs(d, exist_ok=True)
    torch.manual_seed(1234)
    cfg = CLIPTextConfig(vocab_size=49408, hidden_size=h, intermediate_size=inter, num_hidden_layers=nl, num_attention_heads=nh,
                         max_position_embeddings=77, hidden_act=act, layer_norm_eps=1e-5, projection_dim=h)
    m = CLIPTextModelWithProjection(cfg).eval().float()
    with torch.no_grad():
        for n, p in m.named_parameters():
            if "layer_norm" in n and n.endswith("weight"): p.copy_(1.0 + 0.1 * torch.randn_like(p))
            elif p.dim() == 1: p.copy_(0.1 * torch.randn_like(p))
            elif "embedding" in n: p.copy_(0.1 * torch.randn_like(p))
            else: p.copy_((0.7 / p.shape[1] ** 0.5) * torch.randn_like(p))      # keeps activations O(1) through the depth
    from safetensors.torch import save_file
    sd = {k: v.contiguous() for k, v in m.state_dict().items() if "position_ids" not in k}
    save_file(sd, os.path.join(d, "model.safetensors"))
    tok = CLIPTokenizer.from_pretrained(tokdir)
    ids = tok("a photo of a cat sitting on a red sofa", padding="max_length", max_length=77, truncation=True)["input_ids"]
    open(os.path.join(d, "ids.txt"), "w").write(" ".join(map(str, ids)) + "\n")
    with torch.no_grad():
        out = m(input_ids=torch.tensor([ids]), output_hidden_states=True)
    eos = int(np.argmax(np.array(ids)))
    out.last_hidden_state[0].numpy().astype(np.float32).tofile(os.path.join(d, "expect_last.f32"))
    out.hidden_states[-2][0].numpy().astype(np.float32).tofile(os.path.join(d, "expect_pen.f32"))
    out.text_embeds[0].numpy().astype(np.float32).tofile(os.path.join(d, "expect_proj.f32"))
    print("eos position", eos, "config", name, "params", sum(p.numel() for p in m.parameters()))
if __name__ == "__main__":
    write(sys.argv[2], sys.argv[3], sys.argv[4])
