#!/usr/bin/env python3
"""Local test fixture and oracle for vae_metal.kotoba.in (not a gate, not a route).
  python3 make_fixture.py write DIR COMFY_SRC CH L [taps]
Builds ComfyUI's own VAE Decoder (comfy/ldm/modules/diffusionmodules/model.py: ch CH, ch_mult (1, 2, 4, 4), 2 residual blocks per level,
z_channels 4, no attention at the up levels, a single-head attention block in the middle) plus the 1x1 post_quant_conv of AutoencoderKL,
with RANDOM weights (float32, CPU), and writes in DIR:
  model.safetensors  state dict: the decoder's names prefixed nowhere ("conv_in.weight", "mid.attn_1.q.weight", "up.3.block.0.norm1.weight",
                     ...), plus post_quant_conv.weight / .bias
  z.f32              the latent (1 x 4 x L x L)
  expect_final.f32   decoder(post_quant_conv(z)), 1 x 3 x 8L x 8L
  expect_<tap>.f32   (when `taps` is given) activations: cin (conv_in), mid (after mid.block_2), l3 l2 l1 l0 (after each up level, upsampled)
CH = 32 is the small test size (mid width 128), CH = 128 is SDXL's VAE (mid width 512, 84 M parameters).
Needs torch, numpy, safetensors, einops and the ComfyUI v0.44.0 source with the comfy_kitchen / comfy_aimdo stubs on PYTHONPATH."""
import sys, os, logging
logging.disable(logging.CRITICAL)

def write(d, comfy, ch, L, taps):
    sys.path.insert(0, comfy)
    import numpy as np, torch, comfy.ops
    from comfy.ldm.modules.diffusionmodules.model import Decoder
    from safetensors.torch import save_file
    os.makedirs(d, exist_ok=True)
    torch.manual_seed(5)
    dec = Decoder(ch=ch, out_ch=3, ch_mult=(1, 2, 4, 4), num_res_blocks=2, attn_resolutions=[], in_channels=3, resolution=8 * L,
                  z_channels=4).eval().float()
    pq = torch.nn.Conv2d(4, 4, 1).float()
    with torch.no_grad():
        for n, p in list(dec.named_parameters()) + [("post_quant_conv." + k, v) for k, v in pq.named_parameters()]:
            if n.endswith("norm.weight") or "norm1.weight" in n or "norm2.weight" in n or n == "norm_out.weight":
                p.copy_(1.0 + 0.1 * torch.randn_like(p))
            elif p.dim() == 1: p.copy_(0.1 * torch.randn_like(p))
            else: p.copy_(torch.randn_like(p) * (0.7 / (p.shape[1] * p.shape[2] * p.shape[3]) ** 0.5))
    sd = {k: v.contiguous() for k, v in dec.state_dict().items()}
    sd.update({"post_quant_conv." + k: v.contiguous() for k, v in pq.state_dict().items()})
    save_file(sd, os.path.join(d, "model.safetensors"))
    z = torch.randn(1, 4, L, L)
    z.numpy().astype(np.float32).tofile(os.path.join(d, "z.f32"))
    tp = {}
    def hook(name):
        def f(mod, inp, out): tp[name] = out.detach()
        return f
    if taps:
        dec.conv_in.register_forward_hook(hook("cin")); dec.mid.block_2.register_forward_hook(hook("mid"))
        for i in range(4):
            dec.up[i].block[-1].register_forward_hook(hook("b%d" % i))
            if i != 0: dec.up[i].upsample.register_forward_hook(hook("l%d" % i))
    with torch.no_grad():
        out = dec(pq(z))
    out.numpy().astype(np.float32).tofile(os.path.join(d, "expect_final.f32"))
    for k, v in tp.items(): v.numpy().astype(np.float32).tofile(os.path.join(d, "expect_%s.f32" % k))
    print("params", sum(p.numel() for p in dec.parameters()), "out", tuple(out.shape), "max|out|", float(out.abs().max()), "taps", {k: tuple(v.shape) for k, v in tp.items()})

if __name__ == "__main__":
    write(sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), len(sys.argv) > 6)
