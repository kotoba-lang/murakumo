#!/usr/bin/env python3
"""Local test fixture and oracle for unet_sdxl_named_metal.kotoba.in (not a gate, not a route).
  python3 make_fixture.py write DIR COMFY_SRC MC LAT [strong]
Builds ComfyUI's own UNetModel with SDXL's topology (ComfyUI's supported_models.SDXL config: channel_mult (1, 2, 4), two residual blocks per
level, transformer depths 0 / 2 / 10 at the three levels (10 in the middle block and in the output blocks of the last level, [0, 0, 0, 2, 2, 2,
10, 10, 10] on the way out), linear projections, context width 2048, num_head_channels 64, label embedding Linear(2816 -> 4 MC) -> SiLU ->
Linear) but with model_channels MC instead of 320 (MC = 64 gives widths 64 / 128 / 256 and about 90 M parameters; MC = 320 is SDXL's 2.57 B).
RANDOM weights, float32, CPU. Writes in DIR: model.safetensors (ComfyUI's names), x.f32 (1 x 4 x LAT x LAT), ctx.f32 (77 x 2048), y.f32 (2816),
expect_final.f32, and the activations after each stage: emb, h0 .. h8 (input blocks), m (middle), o0 .. o8 (output blocks).
`strong` multiplies the output projections of every attention and feed-forward by 3 (used only to give the mutants something to bite on).
Needs torch, numpy, safetensors, einops and the ComfyUI v0.44.0 source with the comfy_kitchen / comfy_aimdo stubs on PYTHONPATH."""
import sys, os, logging
logging.disable(logging.CRITICAL)

def write(d, comfy, mc, lat, BOOST=1.0):
    sys.path.insert(0, comfy)
    import numpy as np, torch, comfy.ops
    from comfy.ldm.modules.diffusionmodules.openaimodel import UNetModel
    from safetensors.torch import save_file
    os.makedirs(d, exist_ok=True)
    torch.manual_seed(91)
    cfg = dict(image_size=lat, in_channels=4, model_channels=mc, out_channels=4, num_res_blocks=[2, 2, 2], dropout=0, channel_mult=(1, 2, 4),
               conv_resample=True, dims=2, num_classes="sequential", adm_in_channels=2816, use_checkpoint=False, num_heads=-1, num_head_channels=64,
               use_scale_shift_norm=False, resblock_updown=False, use_spatial_transformer=True, transformer_depth=[0, 0, 2, 2, 10, 10],
               transformer_depth_output=[0, 0, 0, 2, 2, 2, 10, 10, 10], transformer_depth_middle=10, context_dim=2048,
               use_linear_in_transformer=True, disable_self_attentions=None, legacy=False)
    m = UNetModel(**cfg, operations=comfy.ops.disable_weight_init, device=torch.device("cpu"), dtype=torch.float32).eval()
    with torch.no_grad():
        for n, p in m.named_parameters():
            if n.endswith("norm.weight") or "in_layers.0.weight" in n or "out_layers.0.weight" in n or "norm1.weight" in n or "norm2.weight" in n or "norm3.weight" in n or n == "out.0.weight":
                p.copy_(1.0 + 0.1 * torch.randn_like(p))
            elif p.dim() == 1: p.copy_(0.1 * torch.randn_like(p))
            elif p.dim() == 4: p.copy_(torch.randn_like(p) * (0.5 / (p.shape[1] * p.shape[2] * p.shape[3]) ** 0.5))
            else: p.copy_(torch.randn_like(p) * (0.7 / p.shape[1] ** 0.5) * (BOOST if ("to_out" in n or "ff.net.2" in n) else 1.0))   # BOOST 3 ("strong"): transformer branches matter more, so a wrong layer count or wrong keys shows
    save_file({k: v.contiguous() for k, v in m.state_dict().items()}, os.path.join(d, "model.safetensors"))
    x = torch.randn(1, 4, lat, lat); ctx = torch.randn(1, 77, 2048) * 0.5; y = torch.randn(1, 2816) * 0.5; t = torch.tensor([500.0])
    for n, v in (("x", x), ("ctx", ctx[0]), ("y", y[0])): v.numpy().astype(np.float32).tofile(os.path.join(d, n + ".f32"))
    taps = {}
    def hook(name):
        def f(mod, inp, out): taps[name] = out.detach()
        return f
    # ComfyUI's forward_timestep_embed iterates the layers of each block itself, so hook each block's last layer
    for i in range(9): m.input_blocks[i][-1].register_forward_hook(hook("h%d" % i))
    m.middle_block[-1].register_forward_hook(hook("m"))
    for i in range(9): m.output_blocks[i][-1].register_forward_hook(hook("o%d" % i))
    m.time_embed.register_forward_hook(hook("te")); m.label_emb.register_forward_hook(hook("le"))
    with torch.no_grad():
        out = m(x, t, context=ctx, y=y)
    out.numpy().astype(np.float32).tofile(os.path.join(d, "expect_final.f32"))
    taps["emb"] = taps.pop("te") + taps.pop("le")
    for k, v in taps.items(): v.numpy().astype(np.float32).tofile(os.path.join(d, "expect_%s.f32" % k))
    print("params", sum(p.numel() for p in m.parameters()), "out", tuple(out.shape), "max|out|", float(out.abs().max()), "tensors", len(m.state_dict()))
    print("shapes", {k: tuple(v.shape) for k, v in taps.items()})

if __name__ == "__main__":
    write(sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), 3.0 if len(sys.argv) > 6 and sys.argv[6] == "strong" else 1.0)
