#!/usr/bin/env python3
"""Local test fixture and oracle for pipeline_metal.kotoba.in (not a gate, not a route).
  python3 make_fixture.py write DIR COMFY_SRC MC LAT CH STEPS
Builds, with ComfyUI v0.44.0's own classes and RANDOM weights (float32, CPU): the SDXL-topology UNetModel of ../unet-sdxl-named (model_channels MC),
the VAE Decoder of ../vae-metal (ch CH) with the 1x1 post_quant_conv, runs the whole render the way ComfyUI does it for an eps-prediction
model: ModelSamplingDiscrete sigmas + the `simple` scheduler, the real sample_euler, a model function that scales the input (x / sqrt(sigma^2 + 1)),
maps sigma to the nearest timestep (ModelSamplingDiscrete.timestep), calls the UNet for the conditional and unconditional prompt (context 77 x 2048
and 2,816-wide label vector each) and combines the DENOISED values with classifier-free guidance (scale 4), then decodes the final latent.
Writes DIR/model.safetensors (UNet and VAE tensors under their own names in one file), x.f32 (initial latent = unit noise * sigma_0),
ctx_c.f32 ctx_u.f32 (77 x 2048), y_c.f32 y_u.f32 (2816), expect_step<i>.f32 (the latent after each step), expect_image.f32 (3 x 8LAT x 8LAT).
comfy.samplers / k_diffusion.sampling import the whole model stack, so the real sample_euler, to_d, append_dims and simple_scheduler source text is
taken from the files by ast and executed (as in ../sampler-metal). Needs torch, numpy, safetensors, einops, tqdm and the stubs on PYTHONPATH."""
import sys, os, ast, types, logging
logging.disable(logging.CRITICAL)

def write(d, comfy_src, mc, lat, ch, steps):
    sys.path.insert(0, comfy_src)
    sys.modules.setdefault('torchsde', types.ModuleType('torchsde'))
    import numpy as np, torch, comfy.ops
    import comfy.model_sampling as ms
    from comfy.ldm.modules.diffusionmodules.openaimodel import UNetModel
    from comfy.ldm.modules.diffusionmodules.model import Decoder
    from safetensors.torch import save_file
    from tqdm import trange
    os.makedirs(d, exist_ok=True)
    torch.manual_seed(2024)
    cfg = dict(image_size=lat, in_channels=4, model_channels=mc, out_channels=4, num_res_blocks=[2, 2, 2], dropout=0, channel_mult=(1, 2, 4),
               conv_resample=True, dims=2, num_classes="sequential", adm_in_channels=2816, use_checkpoint=False, num_heads=-1, num_head_channels=64,
               use_scale_shift_norm=False, resblock_updown=False, use_spatial_transformer=True, transformer_depth=[0, 0, 2, 2, 10, 10],
               transformer_depth_output=[0, 0, 0, 2, 2, 2, 10, 10, 10], transformer_depth_middle=10, context_dim=2048,
               use_linear_in_transformer=True, disable_self_attentions=None, legacy=False)
    unet = UNetModel(**cfg, operations=comfy.ops.disable_weight_init, device=torch.device("cpu"), dtype=torch.float32).eval()
    dec = Decoder(ch=ch, out_ch=3, ch_mult=(1, 2, 4, 4), num_res_blocks=2, attn_resolutions=[], in_channels=3, resolution=8 * lat, z_channels=4).eval().float()
    pq = torch.nn.Conv2d(4, 4, 1).float()
    with torch.no_grad():
        for n, p in unet.named_parameters():
            if n.endswith("norm.weight") or "in_layers.0.weight" in n or "out_layers.0.weight" in n or "norm1.weight" in n or "norm2.weight" in n or "norm3.weight" in n or n == "out.0.weight":
                p.copy_(1.0 + 0.1 * torch.randn_like(p))
            elif p.dim() == 1: p.copy_(0.1 * torch.randn_like(p))
            elif p.dim() == 4: p.copy_(torch.randn_like(p) * (0.5 / (p.shape[1] * p.shape[2] * p.shape[3]) ** 0.5))
            else: p.copy_(torch.randn_like(p) * (0.7 / p.shape[1] ** 0.5))
        for n, p in list(dec.named_parameters()) + [("post_quant_conv." + k, v) for k, v in pq.named_parameters()]:
            if n.endswith("norm.weight") or "norm1.weight" in n or "norm2.weight" in n or n == "norm_out.weight":
                p.copy_(1.0 + 0.1 * torch.randn_like(p))
            elif p.dim() == 1: p.copy_(0.1 * torch.randn_like(p))
            else: p.copy_(torch.randn_like(p) * (0.7 / (p.shape[1] * p.shape[2] * p.shape[3]) ** 0.5))
    sd = {k: v.contiguous() for k, v in unet.state_dict().items()}
    sd.update({k: v.contiguous() for k, v in dec.state_dict().items()})
    sd.update({"post_quant_conv." + k: v.contiguous() for k, v in pq.state_dict().items()})
    assert len(sd) == len(unet.state_dict()) + len(dec.state_dict()) + 2
    save_file(sd, os.path.join(d, "model.safetensors"))
    class Cfg: sampling_settings = {"beta_schedule": "linear", "linear_start": 0.00085, "linear_end": 0.012, "timesteps": 1000}
    msd = ms.ModelSamplingDiscrete(Cfg)
    def grab(path, names):
        src = open(comfy_src + path).read(); out = {}
        for n in ast.parse(src).body:
            if isinstance(n, ast.FunctionDef) and n.name in names: out[n.name] = ast.get_source_segment(src, n)
        return out
    sdr = grab("/comfy/samplers.py", {"simple_scheduler"}); kd = grab("/comfy/k_diffusion/sampling.py", {"to_d", "sample_euler"}); ut = grab("/comfy/k_diffusion/utils.py", {"append_dims"})
    ns = {"torch": torch}; exec(sdr["simple_scheduler"], ns); sigmas = ns["simple_scheduler"](msd, steps)
    class U: pass
    u = U(); uns = {"torch": torch}; exec(ut["append_dims"], uns); u.append_dims = uns["append_dims"]
    kns = {"torch": torch, "trange": trange, "utils": u}; exec(kd["to_d"], kns); exec(kd["sample_euler"], kns)
    # the conditioning, as ComfyUI builds it for SDXL: the two towers' penultimate states concatenated L then G (SDXLClipModel.encode_token_weights),
    # the pooled vector of the G tower, and SDXL.encode_adm (pooled, then Timestep(256) embeddings of height, width, crop_h, crop_w, target_height,
    # target_width); the real encode_adm / sdxl_pooled source text is taken from model_base.py by ast
    from comfy.ldm.modules.diffusionmodules.openaimodel import Timestep
    src = open(comfy_src + "/comfy/model_base.py").read(); tree = ast.parse(src)
    sdxl_cls = [n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "SDXL"][0]
    enc = [n for n in sdxl_cls.body if isinstance(n, ast.FunctionDef) and n.name == "encode_adm"][0]
    pooled_fn = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "sdxl_pooled"][0]
    ns2 = {"torch": torch}; exec(ast.get_source_segment(src, pooled_fn), ns2)
    import textwrap; exec(textwrap.dedent(ast.get_source_segment(src, enc)).replace("def encode_adm", "def encode_adm_fn"), dict(ns2, sdxl_pooled=ns2["sdxl_pooled"]), ns2)
    class FakeSDXL: pass
    fs = FakeSDXL(); fs.embedder = Timestep(256); fs.noise_augmentor = None
    sizes = dict(height=256, width=384, crop_h=16, crop_w=32, target_height=512, target_width=640)
    def cond(tag):
        lpen = torch.randn(1, 77, 768) * 0.8; gpen = torch.randn(1, 77, 1280) * 0.8; gpool = torch.randn(1, 1280) * 0.5
        ctx = torch.cat([lpen, gpen], dim=-1)
        y = ns2["encode_adm_fn"](fs, pooled_output=gpool, **sizes)
        for n, v in (("l" + tag, lpen[0]), ("g" + tag, gpen[0]), ("p" + tag, gpool[0]), ("ctx_" + tag, ctx[0]), ("y_" + tag, y[0])): v.numpy().astype(np.float32).tofile(os.path.join(d, n + ".f32"))
        return ctx, y
    ctx_c, y_c = cond("c"); ctx_u, y_u = cond("u")
    torch.tensor([sizes[k] for k in ("height", "width", "crop_h", "crop_w", "target_height", "target_width")], dtype=torch.float32).numpy().tofile(os.path.join(d, "sz.f32"))
    x0 = torch.randn(1, 4, lat, lat) * sigmas[0]; x0.numpy().astype(np.float32).tofile(os.path.join(d, "x.f32"))
    cfg_scale = 4.0
    calls = []
    def model(x, sigma, **kw):
        s = sigma.view(-1, 1, 1, 1)
        xin = x / torch.sqrt(s ** 2 + 1.0)                      # EPS.calculate_input
        t = msd.timestep(sigma)                                  # nearest discrete timestep in log-sigma
        calls.append(float(t[0]))
        with torch.no_grad():
            ec = unet(xin, t, context=ctx_c, y=y_c); eu = unet(xin, t, context=ctx_u, y=y_u)
        dc = x - ec * s; du = x - eu * s                         # EPS.calculate_denoised
        return du + (dc - du) * cfg_scale
    taps = []
    def callback(info): taps.append(info["x"].clone())
    xr = kns["sample_euler"](model, x0.clone(), sigmas, disable=True, callback=callback)
    # the callback sees x BEFORE each step; the latent after step i is the next one (the last one is the sampler's return value)
    after = taps[1:] + [xr]
    for i, v in enumerate(after): v.numpy().astype(np.float32).tofile(os.path.join(d, "expect_step%d.f32" % i))
    with torch.no_grad(): img = dec(pq(xr))
    img.numpy().astype(np.float32).tofile(os.path.join(d, "expect_image.f32"))
    print("sigmas", [round(float(s), 3) for s in sigmas], "timesteps", calls[::1], "latent max|x|", float(xr.abs().max()), "image", tuple(img.shape), "max|img|", float(img.abs().max()))

if __name__ == "__main__":
    write(sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), int(sys.argv[6]), int(sys.argv[7]))
