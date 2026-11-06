#!/usr/bin/env python3
"""Local test fixture and oracle for pipeline_metal.kotoba.in (not a gate, not a route).
  python3 make_fixture.py write DIR COMFY_SRC MC LAT CH STEPS TOKDIR [hf|single]
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

def write(d, comfy_src, mc, lat, ch, steps, tokdir, layout="hf"):
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
    # the two SDXL text towers at full size (slice 15): OpenAI CLIP-L (12 x 768, quick_gelu) and OpenCLIP bigG (32 x 1280, gelu, with text_projection), random weights
    from transformers import CLIPTextConfig, CLIPTextModelWithProjection, CLIPTokenizer
    def tower(nl, h, nh, inter, act):
        m = CLIPTextModelWithProjection(CLIPTextConfig(vocab_size=49408, hidden_size=h, intermediate_size=inter, num_hidden_layers=nl, num_attention_heads=nh,
                                                       max_position_embeddings=77, hidden_act=act, layer_norm_eps=1e-5, projection_dim=h)).eval().float()
        with torch.no_grad():
            for n, p in m.named_parameters():
                if "layer_norm" in n and n.endswith("weight"): p.copy_(1.0 + 0.1 * torch.randn_like(p))
                elif p.dim() == 1: p.copy_(0.1 * torch.randn_like(p))
                elif "embedding" in n: p.copy_(0.1 * torch.randn_like(p))
                else: p.copy_((0.7 / p.shape[1] ** 0.5) * torch.randn_like(p))
        return m
    clip_l = tower(12, 768, 12, 3072, "quick_gelu"); clip_g = tower(32, 1280, 20, 5120, "gelu")
    if layout == "single":
        # the layout and dtype of a real SDXL single-file checkpoint: float16, UNet under model.diffusion_model., VAE under first_stage_model., CLIP-L in Hugging Face
        # names under conditioner.embedders.0.transformer., OpenCLIP-bigG in OpenCLIP names under conditioner.embedders.1.model. (q, k, v stacked into in_proj, ln_1/ln_2,
        # mlp.c_fc/c_proj, ln_final, positional_embedding, text_projection stored [in, out]) -- the inverse of comfy/diffusers_convert.py's textenc_conversion_lst.
        # The weights are rounded to float16 first, so the oracle (which uses these same tensors in float32) and the file hold identical values.
        with torch.no_grad():
            for mdl in (unet, dec, pq, clip_l, clip_g):
                for p in mdl.parameters(): p.copy_(p.half().float())
        sd = {}
        for k, v in unet.state_dict().items(): sd["model.diffusion_model." + k] = v.half().contiguous()
        for k, v in dec.state_dict().items(): sd["first_stage_model.decoder." + k] = v.half().contiguous()
        for k, v in pq.state_dict().items(): sd["first_stage_model.post_quant_conv." + k] = v.half().contiguous()
        for k, v in clip_l.state_dict().items():
            if k.startswith("text_model.") and "position_ids" not in k: sd["conditioner.embedders.0.transformer." + k] = v.half().contiguous()
        g = clip_g.state_dict(); P = "conditioner.embedders.1.model."
        sd[P + "token_embedding.weight"] = g["text_model.embeddings.token_embedding.weight"].half().contiguous()
        sd[P + "positional_embedding"] = g["text_model.embeddings.position_embedding.weight"].half().contiguous()
        for l in range(32):
            hp = "text_model.encoder.layers.%d." % l; op = P + "transformer.resblocks.%d." % l
            for a_, b_ in (("ln_1", "layer_norm1"), ("ln_2", "layer_norm2")):
                for t in ("weight", "bias"): sd[op + a_ + "." + t] = g[hp + b_ + "." + t].half().contiguous()
            for t in ("weight", "bias"):
                sd[op + "attn.in_proj_" + t] = torch.cat([g[hp + "self_attn.%s_proj.%s" % (c, t)] for c in "qkv"], 0).half().contiguous()
                sd[op + "attn.out_proj." + t] = g[hp + "self_attn.out_proj." + t].half().contiguous()
                sd[op + "mlp.c_fc." + t] = g[hp + "mlp.fc1." + t].half().contiguous()
                sd[op + "mlp.c_proj." + t] = g[hp + "mlp.fc2." + t].half().contiguous()
        for t in ("weight", "bias"): sd[P + "ln_final." + t] = g["text_model.final_layer_norm." + t].half().contiguous()
        sd[P + "text_projection"] = g["text_projection.weight"].t().contiguous().half()
        sd[P + "logit_scale"] = torch.tensor(4.6052).half()      # a scalar a real checkpoint carries (shape [])
        # things a real checkpoint also holds that this loader must index and leave alone, or read in another dtype: the VAE encoder, EMA / schedule buffers,
        # and a few float32 tensors inside the float16 file (here the UNet output norm and the bigG final LayerNorm)
        gen = torch.Generator().manual_seed(7)
        for k_, shp in (("first_stage_model.encoder.conv_in.weight", (128, 3, 3, 3)), ("first_stage_model.encoder.mid.attn_1.q.weight", (512, 512, 1, 1)),
                        ("first_stage_model.quant_conv.weight", (8, 8, 1, 1)), ("model_ema.decay", ()), ("model_ema.num_updates", ())):
            sd[k_] = (torch.randn(shp, generator=gen) * 0.05).half()
        sd["alphas_cumprod"] = torch.linspace(0.99, 0.01, 1000).float()
        sd["conditioner.embedders.0.transformer.text_model.embeddings.position_ids"] = torch.arange(77, dtype=torch.int64).reshape(1, 77)   # an int64 buffer a real CLIP-L carries
        for k_ in ("model.diffusion_model.out.0.weight", "model.diffusion_model.out.0.bias", P + "ln_final.weight", P + "ln_final.bias"): sd[k_] = sd[k_].float()
        save_file(sd, os.path.join(d, "model.safetensors"))
    else:
        sd = {k: v.contiguous() for k, v in unet.state_dict().items()}
        for pre, m in (("clip_l.", clip_l), ("clip_g.", clip_g)):
            sd.update({pre + k: v.contiguous() for k, v in m.state_dict().items() if "position_ids" not in k})
        nclip = sum(1 for k in sd if k.startswith("clip_"))
        sd.update({k: v.contiguous() for k, v in dec.state_dict().items()})
        sd.update({"post_quant_conv." + k: v.contiguous() for k, v in pq.state_dict().items()})
        save_file(sd, os.path.join(d, "model.safetensors"))
    class Cfg: sampling_settings = {"beta_schedule": "linear", "linear_start": 0.00085, "linear_end": 0.012, "timesteps": 1000}
    msd = ms.ModelSamplingDiscrete(Cfg)
    def grab(path, names):
        src = open(comfy_src + path).read(); out = {}
        for n in ast.parse(src).body:
            if isinstance(n, ast.FunctionDef) and n.name in names: out[n.name] = ast.get_source_segment(src, n)
        return out
    sdr = grab("/comfy/samplers.py", {"simple_scheduler", "normal_scheduler"}); kd = grab("/comfy/k_diffusion/sampling.py", {"to_d", "sample_euler", "sample_dpmpp_2m", "get_sigmas_karras", "append_zero"}); ut = grab("/comfy/k_diffusion/utils.py", {"append_dims"})
    import math; ns = {"torch": torch, "math": math}; exec(sdr["simple_scheduler"], ns); exec(sdr["normal_scheduler"], ns)
    class U: pass
    u = U(); uns = {"torch": torch}; exec(ut["append_dims"], uns); u.append_dims = uns["append_dims"]
    kns = {"torch": torch, "trange": trange, "utils": u}; exec(kd["to_d"], kns); exec(kd["sample_euler"], kns); exec(kd["sample_dpmpp_2m"], kns); exec(kd["append_zero"], kns); exec(kd["get_sigmas_karras"], kns)
    # the scheduler and sampler under test (ComfyUI's own source text): simple / normal / karras, euler / dpmpp_2m
    sched = os.environ.get("SCHED", "simple"); sampler = os.environ.get("SAMPLER", "euler")
    if sched == "simple": sigmas = ns["simple_scheduler"](msd, steps)
    elif sched == "normal": sigmas = ns["normal_scheduler"](msd, steps)
    elif sched == "karras": sigmas = kns["get_sigmas_karras"](n=steps, sigma_min=float(msd.sigma_min), sigma_max=float(msd.sigma_max))
    else: raise SystemExit("unknown scheduler " + sched)
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
    tok = CLIPTokenizer.from_pretrained(tokdir)
    # ComfyUI's own tokenizers (escape_important / token_weights / the Hugging Face tokenizer per segment): ids and one weight per position
    from comfy.sd1_clip import SDTokenizer
    import subprocess
    embdir = os.path.join(d, "emb")
    subprocess.check_call([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "clip-tokenizer-metal", "make_embeddings.py"), embdir])
    tk_l = SDTokenizer(tokenizer_path=tokdir, max_length=77, pad_with_end=True, embedding_directory=embdir, embedding_size=768, embedding_key="clip_l", has_start_token=True, pad_to_max_length=True)
    tk_g = SDTokenizer(tokenizer_path=tokdir, max_length=77, pad_with_end=False, embedding_directory=embdir, embedding_size=1280, embedding_key="clip_g", has_start_token=True, pad_to_max_length=True)
    def run_block(model, entries):
        # a block of ComfyUI tokens: embedding rows (tensors) are put into spare rows of the token embedding for the forward pass (ids 1, 2, ...), then restored
        w = model.text_model.embeddings.token_embedding.weight; saved = {}; ids = []; nxt = 1
        for x in entries:
            if torch.is_tensor(x[0]):
                saved.setdefault(nxt, w[nxt].clone()); w[nxt] = x[0].to(w.dtype); ids.append(nxt); nxt += 1
            else: ids.append(x[0])
        try: return model(input_ids=torch.tensor([ids]), output_hidden_states=True)
        finally:
            for r, v in saved.items(): w[r] = v
    def blend(z, ze, wts):
        # ClipTokenWeightEncoder.encode_token_weights: z[j] = (z[j] - z_empty[j]) * weight + z_empty[j] where weight != 1.0
        z = z.clone()
        for j, w in enumerate(wts):
            if w != 1.0: z[0][j] = (z[0][j] - ze[0][j]) * w + ze[0][j]
        return z
    def cond(tag, prompt):
        # every block of 77 ComfyUI's tokenizer makes (a prompt of more than 75 tokens has several), each through the towers on its own, then joined
        # along the sequence axis; the pooled vector is the first block's
        bl = tk_l.tokenize_with_weights(prompt); bg = tk_g.tokenize_with_weights(prompt)
        il = [0 if torch.is_tensor(x[0]) else x[0] for b in bl for x in b]; ig = [0 if torch.is_tensor(x[0]) else x[0] for b in bg for x in b]
        open(os.path.join(d, "prompt_" + tag + ".txt"), "w", encoding="utf-8").write(prompt + "\n")
        np.array(il + [0], dtype=np.int32).tofile(os.path.join(d, "ids_l_" + tag + ".i32")); np.array(ig + [0], dtype=np.int32).tofile(os.path.join(d, "ids_g_" + tag + ".i32"))
        with torch.no_grad():
            el = clip_l(input_ids=torch.tensor([[49406, 49407] + [49407] * 75]), output_hidden_states=True)
            eg = clip_g(input_ids=torch.tensor([[49406, 49407] + [0] * 75]), output_hidden_states=True)
            lps, gps, gpool = [], [], None
            for k in range(len(bl)):
                wts = [x[1] for x in bl[k]]
                ol = run_block(clip_l, bl[k]); og = run_block(clip_g, bg[k])
                lpen = ol.hidden_states[-2]; gpen = og.hidden_states[-2]
                if any(w != 1.0 for w in wts):
                    lpen = blend(lpen, el.hidden_states[-2], wts); gpen = blend(gpen, eg.hidden_states[-2], wts)
                lps.append(lpen); gps.append(gpen)
                if k == 0: gpool = og.text_embeds
        ctx = torch.cat([torch.cat(lps, dim=1), torch.cat(gps, dim=1)], dim=-1)
        y = ns2["encode_adm_fn"](fs, pooled_output=gpool, **sizes)
        for n, v in (("ctx_" + tag, ctx[0]), ("y_" + tag, y[0])): v.numpy().astype(np.float32).tofile(os.path.join(d, n + ".f32"))
        return ctx, y
    ctx_c, y_c = cond("c", os.environ.get("PROMPT_C", "a photo of a cat sitting on a red sofa")); ctx_u, y_u = cond("u", os.environ.get("PROMPT_U", ""))
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
    xr = kns["sample_" + sampler](model, x0.clone(), sigmas, disable=True, callback=callback)
    # the callback sees x BEFORE each step; the latent after step i is the next one (the last one is the sampler's return value)
    after = taps[1:] + [xr]
    for i, v in enumerate(after): v.numpy().astype(np.float32).tofile(os.path.join(d, "expect_step%d.f32" % i))
    with torch.no_grad(): img = dec(pq(xr))
    img.numpy().astype(np.float32).tofile(os.path.join(d, "expect_image.f32"))
    print("sigmas", [round(float(s), 3) for s in sigmas], "timesteps", calls[::1], "latent max|x|", float(xr.abs().max()), "image", tuple(img.shape), "max|img|", float(img.abs().max()))

if __name__ == "__main__":
    write(sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), int(sys.argv[6]), int(sys.argv[7]), sys.argv[8], sys.argv[9] if len(sys.argv) > 9 else "hf")
