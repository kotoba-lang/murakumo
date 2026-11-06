import sys, time, logging
logging.disable(logging.CRITICAL)
import torch
import comfy.ops
from comfy.ldm.modules.diffusionmodules.openaimodel import UNetModel
dev = torch.device("mps"); dt = torch.float16
cfg = dict(image_size=96, in_channels=4, model_channels=320, out_channels=4, num_res_blocks=[2, 2, 2], dropout=0, channel_mult=(1, 2, 4),
           conv_resample=True, dims=2, num_classes="sequential", adm_in_channels=2816, use_checkpoint=False, num_heads=-1, num_head_channels=64,
           use_scale_shift_norm=False, resblock_updown=False, use_spatial_transformer=True, transformer_depth=[0, 0, 2, 2, 10, 10],
           transformer_depth_output=[0, 0, 0, 2, 2, 2, 10, 10, 10], transformer_depth_middle=10, context_dim=2048,
           use_linear_in_transformer=True, transformer_depth_output_unused=None)
cfg.pop("transformer_depth_output_unused")
t0 = time.time()
m = UNetModel(**cfg, operations=comfy.ops.disable_weight_init, device=dev, dtype=dt).eval()
for p in m.parameters():
    p.data.normal_(0, 0.02)
torch.mps.synchronize(); print("built", sum(p.numel() for p in m.parameters()), "params in %.0f s" % (time.time() - t0), flush=True)
ctx = torch.randn(1, 77, 2048, device=dev, dtype=dt); y = torch.randn(1, 2816, device=dev, dtype=dt)
for lat in (64, 96, 128):
    x = torch.randn(1, 4, lat, lat, device=dev, dtype=dt); ts = torch.tensor([500.0], device=dev)
    with torch.no_grad():
        for _ in range(2): m(x, ts, ctx, y=y); torch.mps.synchronize()
        best = 1e9; tt = []
        for _ in range(5):
            t0 = time.time(); o = m(x, ts, ctx, y=y); torch.mps.synchronize(); tt.append(time.time() - t0)
    print("latent %d (%d px): best %.3f s, mean %.3f s, finite=%s" % (lat, lat * 8, min(tt), sum(tt) / len(tt), bool(torch.isfinite(o).all())), flush=True)
