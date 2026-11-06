#!/usr/bin/env python3
"""Local test fixture and oracle for clip_tower_metal.kotoba.in (not a gate, not a route).
  python3 make_fixture.py write DIR     write DIR/model.safetensors (a small Hugging Face CLIPTextModel with random weights),
                                        DIR/ids.txt (77 token ids of a prompt, padded with the end token) and DIR/expect.f32
                                        (the model's last_hidden_state, 77 x 128 float32, computed in float32 on the CPU) and
                                        DIR/expect_pooled.f32 (the hidden state at the end token, 128 float32)
Needs torch, transformers, safetensors, numpy (python3.11 with transformers 5.4.0, torch 2.6.0 were used)."""
import sys, os
import numpy as np, torch
from transformers import CLIPTextConfig, CLIPTextModel, CLIPTokenizer

def write(d, tokdir):
    os.makedirs(d, exist_ok=True)
    torch.manual_seed(1234)
    cfg = CLIPTextConfig(vocab_size=49408, hidden_size=128, intermediate_size=512, num_hidden_layers=2, num_attention_heads=2,
                         max_position_embeddings=77, hidden_act="quick_gelu", layer_norm_eps=1e-5, projection_dim=64)
    m = CLIPTextModel(cfg).eval().float()
    with torch.no_grad():                      # random init is near-zero for LN; give every parameter some spread
        for n, p in m.named_parameters():
            if "layer_norm" in n and n.endswith("weight"): p.copy_(1.0 + 0.1 * torch.randn_like(p))
            elif p.dim() == 1: p.copy_(0.1 * torch.randn_like(p))
            else: p.copy_(0.08 * torch.randn_like(p))
    from safetensors.torch import save_file
    sd = {k: v.contiguous() for k, v in m.state_dict().items() if "position_ids" not in k}
    save_file(sd, os.path.join(d, "model.safetensors"))
    tok = CLIPTokenizer.from_pretrained(tokdir)
    ids = tok("a photo of a cat sitting on a red sofa", padding="max_length", max_length=77, truncation=True)["input_ids"]
    open(os.path.join(d, "ids.txt"), "w").write(" ".join(map(str, ids)) + "\n")
    with torch.no_grad():
        out = m(input_ids=torch.tensor([ids]))
    last = out.last_hidden_state[0].numpy().astype(np.float32)
    last.tofile(os.path.join(d, "expect.f32"))
    eos = int(np.argmax(np.array(ids)))
    last[eos].tofile(os.path.join(d, "expect_pooled.f32"))
    print("eos position", eos, "ids", ids[:12], "...")

if __name__ == "__main__":
    write(sys.argv[2], sys.argv[3])
