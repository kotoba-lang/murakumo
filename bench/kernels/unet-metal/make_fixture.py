#!/usr/bin/env python3
"""Local test fixture and oracle for unet_metal.kotoba.in (not a gate, not a route).
  python3 make_fixture.py write DIR COMFY_SRC
Builds ComfyUI's own UNetModel (comfy/ldm/modules/diffusionmodules/openaimodel.py) in a mini configuration (4 -> 4 channels, model_channels 32,
channel_mult (1, 2), one res block per level, a SpatialTransformer of depth 1 at the second level, the middle block and the first two output
blocks of the second level, linear projections, context width 24, no class embedding), gives every parameter a random spread, and writes in DIR:
  model.safetensors   the state dict (ComfyUI's names), x.f32 (1 x 4 x 16 x 16), ctx.f32 (16 x 24), t.txt (the timestep)
  expect_final.f32    the output of model(x, t, context=ctx), float32 on the CPU
  expect_<stage>.f32  the activations after: h0 (input_blocks.0), b1 (input_blocks.1), d (input_blocks.2), b3 (input_blocks.3),
                      m (middle_block), o0 o1 o2 o3 (output_blocks.0..3), emb (time_embed)
Needs torch, numpy, safetensors, einops and the ComfyUI v0.44.0 source with the optional comfy_kitchen / comfy_aimdo stubs on PYTHONPATH
(bench/oracle/comfyui/stubs)."""
import sys, os, logging
logging.disable(logging.CRITICAL)

def write(d, comfy):
    sys.path.insert(0, comfy)
    import numpy as np, torch, comfy.ops
    from comfy.ldm.modules.diffusionmodules.openaimodel import UNetModel
    from safetensors.torch import save_file
    os.makedirs(d, exist_ok=True)
    torch.manual_seed(77)
    cfg = dict(image_size=16, in_channels=4, model_channels=32, out_channels=4, num_res_blocks=[1, 1], dropout=0, channel_mult=(1, 2),
               conv_resample=True, dims=2, num_classes=None, use_checkpoint=False, num_heads=-1, num_head_channels=64,
               use_scale_shift_norm=False, resblock_updown=False, use_spatial_transformer=True, transformer_depth=[0, 1],
               transformer_depth_output=[0, 0, 1, 1], transformer_depth_middle=1, context_dim=24, use_linear_in_transformer=True,
               disable_self_attentions=None, legacy=False)
    m = UNetModel(**cfg, operations=comfy.ops.disable_weight_init, device=torch.device("cpu"), dtype=torch.float32).eval()
    with torch.no_grad():
        for n, p in m.named_parameters():
            if n.endswith("norm.weight") or "in_layers.0.weight" in n or "out_layers.0.weight" in n or "norm1.weight" in n or "norm2.weight" in n or "norm3.weight" in n or n == "out.0.weight":
                p.copy_(1.0 + 0.1 * torch.randn_like(p))
            elif p.dim() == 1: p.copy_(0.1 * torch.randn_like(p))
            elif p.dim() == 4: p.copy_(torch.randn_like(p) * (0.5 / (p.shape[1] * p.shape[2] * p.shape[3]) ** 0.5))
            else: p.copy_(torch.randn_like(p) * (0.7 / p.shape[1] ** 0.5))
    save_file({k: v.contiguous() for k, v in m.state_dict().items()}, os.path.join(d, "model.safetensors"))
    x = torch.randn(1, 4, 16, 16); ctx = torch.randn(1, 16, 24) * 0.5; t = torch.tensor([500.0])
    x.numpy().astype(np.float32).tofile(os.path.join(d, "x.f32")); ctx.numpy().astype(np.float32).tofile(os.path.join(d, "ctx.f32"))
    open(os.path.join(d, "t.txt"), "w").write("500\n")
    taps = {}
    def hook(name):
        def f(mod, inp, out): taps[name] = out.detach()
        return f
    # ComfyUI's forward_timestep_embed iterates the layers of each block itself, so hook each block's last layer
    m.input_blocks[0][-1].register_forward_hook(hook("h0")); m.input_blocks[1][-1].register_forward_hook(hook("b1"))
    m.input_blocks[2][-1].register_forward_hook(hook("d")); m.input_blocks[3][-1].register_forward_hook(hook("b3"))
    m.middle_block[-1].register_forward_hook(hook("m"))
    for i in range(4): m.output_blocks[i][-1].register_forward_hook(hook(f"o{i}"))
    m.time_embed.register_forward_hook(hook("emb"))
    with torch.no_grad():
        out = m(x, t, context=ctx)
    out.numpy().astype(np.float32).tofile(os.path.join(d, "expect_final.f32"))
    for k, v in taps.items(): v.numpy().astype(np.float32).tofile(os.path.join(d, f"expect_{k}.f32"))
    print("params", sum(p.numel() for p in m.parameters()), "out", tuple(out.shape), "max|out|", float(out.abs().max()))
    print("stage shapes", {k: tuple(v.shape) for k, v in taps.items()})

if __name__ == "__main__":
    write(sys.argv[2], sys.argv[3])
