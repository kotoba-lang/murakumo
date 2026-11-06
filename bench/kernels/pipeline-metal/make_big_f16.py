#!/usr/bin/env python3
"""Streams a REAL-SIZE SDXL single-file checkpoint with random weights, float16, to DIR/model.safetensors (about 6.9 GB), plus the small inputs the render
program needs (x.f32, prompt_c.txt, prompt_u.txt, sz.f32). No oracle: this is a memory / time / finiteness check of the loader at the real file size.
  python3 make_big_f16.py DIR COMFY_SRC LAT
Layout as make_fixture.py `single`: UNet (model_channels 320) under model.diffusion_model., VAE decoder (ch 128) under first_stage_model.decoder. (+ post_quant_conv,
and a stand-in encoder of the right size is NOT written), CLIP-L under conditioner.embedders.0.transformer. (HF names), bigG under conditioner.embedders.1.model. (OpenCLIP)."""
import sys, os, json, struct, math, logging
logging.disable(logging.CRITICAL)
import warnings; warnings.filterwarnings("ignore")
d, comfy_src, lat = sys.argv[1], sys.argv[2], int(sys.argv[3])
sys.path.insert(0, comfy_src)
import numpy as np, torch, comfy.ops
from comfy.ldm.modules.diffusionmodules.openaimodel import UNetModel
from comfy.ldm.modules.diffusionmodules.model import Decoder
from transformers import CLIPTextConfig, CLIPTextModelWithProjection
os.makedirs(d, exist_ok=True)
meta = torch.device("meta")
cfg = dict(image_size=lat, in_channels=4, model_channels=320, out_channels=4, num_res_blocks=[2, 2, 2], dropout=0, channel_mult=(1, 2, 4), conv_resample=True, dims=2,
           num_classes="sequential", adm_in_channels=2816, use_checkpoint=False, num_heads=-1, num_head_channels=64, use_scale_shift_norm=False, resblock_updown=False,
           use_spatial_transformer=True, transformer_depth=[0, 0, 2, 2, 10, 10], transformer_depth_output=[0, 0, 0, 2, 2, 2, 10, 10, 10], transformer_depth_middle=10,
           context_dim=2048, use_linear_in_transformer=True, disable_self_attentions=None, legacy=False)
unet = UNetModel(**cfg, operations=comfy.ops.disable_weight_init, device=meta, dtype=torch.float32)
with torch.device("meta"):
    dec = Decoder(ch=128, out_ch=3, ch_mult=(1, 2, 4, 4), num_res_blocks=2, attn_resolutions=[], in_channels=3, resolution=8 * lat, z_channels=4)
    def tw(nl, h, nh, inter, act): return CLIPTextModelWithProjection(CLIPTextConfig(vocab_size=49408, hidden_size=h, intermediate_size=inter, num_hidden_layers=nl, num_attention_heads=nh, max_position_embeddings=77, hidden_act=act, layer_norm_eps=1e-5, projection_dim=h))
    clip_l = tw(12, 768, 12, 3072, "quick_gelu"); clip_g = tw(32, 1280, 20, 5120, "gelu")
entries = []     # (key, shape, kind) kind: 'gamma','1d','conv4','lin'
def kind(n, sh):
    if n.endswith("norm.weight") or "in_layers.0.weight" in n or "out_layers.0.weight" in n or "norm1.weight" in n or "norm2.weight" in n or "norm3.weight" in n or n == "out.0.weight" or "layer_norm" in n and n.endswith("weight") or n == "norm_out.weight" or "ln_" in n and n.endswith("weight"): return "gamma"
    if len(sh) == 1: return "1d"
    if "embedding" in n: return "emb"
    if len(sh) == 4: return "conv4"
    return "lin"
for k, p in unet.named_parameters(): entries.append(("model.diffusion_model." + k, tuple(p.shape), kind(k, p.shape)))
for k, p in dec.named_parameters(): entries.append(("first_stage_model.decoder." + k, tuple(p.shape), kind(k, p.shape)))
entries += [("first_stage_model.post_quant_conv.weight", (4, 4, 1, 1), "conv4"), ("first_stage_model.post_quant_conv.bias", (4,), "1d")]
for k, p in clip_l.named_parameters():
    if k.startswith("text_model."): entries.append(("conditioner.embedders.0.transformer." + k, tuple(p.shape), kind(k, p.shape)))
P = "conditioner.embedders.1.model."; g = {k: tuple(p.shape) for k, p in clip_g.named_parameters()}
entries += [(P + "token_embedding.weight", g["text_model.embeddings.token_embedding.weight"], "emb"), (P + "positional_embedding", g["text_model.embeddings.position_embedding.weight"], "emb")]
for l in range(32):
    op = P + "transformer.resblocks.%d." % l
    for a_ in ("ln_1", "ln_2"): entries += [(op + a_ + ".weight", (1280,), "gamma"), (op + a_ + ".bias", (1280,), "1d")]
    entries += [(op + "attn.in_proj_weight", (3840, 1280), "lin"), (op + "attn.in_proj_bias", (3840,), "1d"), (op + "attn.out_proj.weight", (1280, 1280), "lin"), (op + "attn.out_proj.bias", (1280,), "1d"),
                (op + "mlp.c_fc.weight", (5120, 1280), "lin"), (op + "mlp.c_fc.bias", (5120,), "1d"), (op + "mlp.c_proj.weight", (1280, 5120), "lin"), (op + "mlp.c_proj.bias", (1280,), "1d")]
entries += [(P + "ln_final.weight", (1280,), "gamma"), (P + "ln_final.bias", (1280,), "1d"), (P + "text_projection", (1280, 1280), "lin"), (P + "logit_scale", (), "1d")]
hdr = {}; off = 0
for k, sh, kd in entries:
    nb = 2 * math.prod(sh); hdr[k] = {"dtype": "F16", "shape": list(sh), "data_offsets": [off, off + nb]}; off += nb
hj = json.dumps(hdr, separators=(",", ":")).encode(); hj += b" " * ((8 - len(hj) % 8) % 8)
gen = torch.Generator().manual_seed(11)
with open(os.path.join(d, "model.safetensors"), "wb") as fh:
    fh.write(struct.pack("<Q", len(hj))); fh.write(hj)
    for k, sh, kd in entries:
        r = torch.randn(sh, generator=gen) if sh else torch.randn(1, generator=gen)[0]
        if kd == "gamma": t = 1.0 + 0.1 * r
        elif kd == "1d": t = 0.1 * r
        elif kd == "emb": t = 0.1 * r
        elif kd == "conv4": t = r * (0.5 / (sh[1] * sh[2] * sh[3]) ** 0.5)
        else: t = r * (0.7 / (sh[1] if len(sh) > 1 and "text_projection" not in k else sh[0]) ** 0.5)
        fh.write(t.half().numpy().tobytes())
sigma0 = 14.6146
(torch.randn(1, 4, lat, lat, generator=gen) * sigma0).numpy().astype(np.float32).tofile(os.path.join(d, "x.f32"))
open(os.path.join(d, "prompt_c.txt"), "w").write("a photo of a cat sitting on a red sofa\n"); open(os.path.join(d, "prompt_u.txt"), "w").write("\n")
torch.tensor([lat * 8, lat * 8, 0, 0, lat * 8, lat * 8], dtype=torch.float32).numpy().tofile(os.path.join(d, "sz.f32"))
print("tensors", len(entries), "bytes", off + 8 + len(hj))
