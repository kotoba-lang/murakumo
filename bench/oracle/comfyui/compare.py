#!/usr/bin/env python3
"""One-off oracle: run ComfyUI's own PyTorch ResBlock / SpatialTransformer on the
parameters and inputs a C twin dumped (DUMP_OUT, see bench/kernels/*/kernel.c) and
compare the result with the twin's output.

This is a local measurement, not a gate and not a route: nothing in CI, a build or
a runtime path may call it (owner decision 2026-10-29/11-06). The Kotoba kernels
are bit-exact against the twin (checksum gate, bench/kernels/gate.sh); this script
measures how far that twin is from ComfyUI's own code on the same numbers.

  python3 compare.py --comfy-src <ComfyUI checkout> --kind resblock|spatial \
      --dump <file> --shape CI=32 CO=64 H=16 W=16 G=32 TE=64
  python3 compare.py --comfy-src ... --kind spatial --dump <file> \
      --shape SC=32 SH=8 SW=8 SG=32 SNH=2 SLC=16 SCC=24 SFI=128

ComfyUI hard-wires 32 GroupNorm groups and 1e-6 for the transformer's GroupNorm
(the twin takes GNEPS), so shapes must use G = SG = 32 and GNEPS=0.000001.
"""
import argparse, json, sys, os
import numpy as np
import torch

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--comfy-src", required=True)
    ap.add_argument("--kind", required=True, choices=["resblock", "spatial"])
    ap.add_argument("--dump", required=True)
    ap.add_argument("--shape", nargs="+", required=True)
    ap.add_argument("--tol", type=float, default=5e-5)
    a = ap.parse_args()
    sh = {k: int(v) for k, v in (s.split("=") for s in a.shape)}
    sys.path.insert(0, a.comfy_src)
    import logging; logging.disable(logging.CRITICAL)  # comfy_kitchen / fp8 import noise
    import comfy.ops
    ops = comfy.ops.disable_weight_init
    raw = np.fromfile(a.dump, dtype=np.float32)
    pos = [0]
    def take(*shape):
        n = int(np.prod(shape)); t = torch.from_numpy(raw[pos[0]:pos[0] + n].copy()).reshape(shape); pos[0] += n; return t
    torch.set_num_threads(1)
    with torch.no_grad():
        if a.kind == "resblock":
            from comfy.ldm.modules.diffusionmodules.openaimodel import ResBlock
            CI, CO, H, W, G, TE = (sh[k] for k in ("CI", "CO", "H", "W", "G", "TE"))
            assert G == 32, "ComfyUI's ResBlock uses 32 groups"
            x = take(1, CI, H, W); temb = take(1, TE)
            m = ResBlock(CI, TE, 0.0, out_channels=CO, dims=2, operations=ops).float().eval()
            sd = {}
            sd["in_layers.0.weight"] = take(CI); sd["in_layers.0.bias"] = take(CI)
            sd["in_layers.2.weight"] = take(CO, CI, 3, 3); sd["in_layers.2.bias"] = take(CO)
            sd["emb_layers.1.weight"] = take(TE, CO).t().contiguous(); sd["emb_layers.1.bias"] = take(CO)
            sd["out_layers.0.weight"] = take(CO); sd["out_layers.0.bias"] = take(CO)
            sd["out_layers.3.weight"] = take(CO, CO, 3, 3); sd["out_layers.3.bias"] = take(CO)
            sd["skip_connection.weight"] = take(CO, CI, 1, 1); sd["skip_connection.bias"] = take(CO)
            twin = take(1, CO, H, W)
            run = lambda: m(x, temb)
        else:
            from comfy.ldm.modules.attention import SpatialTransformer
            C, H, W, G, NH, LC, CC, FI = (sh[k] for k in ("SC", "SH", "SW", "SG", "SNH", "SLC", "SCC", "SFI"))
            assert G == 32 and FI == 4 * C, "ComfyUI: 32 groups, feed-forward inner width 4*C"
            x = take(1, C, H, W); ctx = take(1, LC, CC)
            m = SpatialTransformer(C, NH, C // NH, depth=1, context_dim=CC, use_linear=True, use_checkpoint=False, operations=ops).float().eval()
            t = lambda *s: take(*s)
            lin = lambda k, n: take(k, n).t().contiguous()   # twin stores [in, out]; torch Linear is [out, in]
            sd = {}
            sd["norm.weight"] = t(C); sd["norm.bias"] = t(C)
            sd["proj_in.weight"] = lin(C, C); sd["proj_in.bias"] = t(C)
            b = "transformer_blocks.0."
            sd[b + "norm1.weight"] = t(C); sd[b + "norm1.bias"] = t(C)
            sd[b + "attn1.to_q.weight"] = lin(C, C); sd[b + "attn1.to_k.weight"] = lin(C, C); sd[b + "attn1.to_v.weight"] = lin(C, C)
            sd[b + "attn1.to_out.0.weight"] = lin(C, C); sd[b + "attn1.to_out.0.bias"] = t(C)
            sd[b + "norm2.weight"] = t(C); sd[b + "norm2.bias"] = t(C)
            sd[b + "attn2.to_q.weight"] = lin(C, C); sd[b + "attn2.to_k.weight"] = lin(CC, C); sd[b + "attn2.to_v.weight"] = lin(CC, C)
            sd[b + "attn2.to_out.0.weight"] = lin(C, C); sd[b + "attn2.to_out.0.bias"] = t(C)
            sd[b + "norm3.weight"] = t(C); sd[b + "norm3.bias"] = t(C)
            sd[b + "ff.net.0.proj.weight"] = lin(C, 2 * FI); sd[b + "ff.net.0.proj.bias"] = t(2 * FI)
            sd[b + "ff.net.2.weight"] = lin(FI, C); sd[b + "ff.net.2.bias"] = t(C)
            sd["proj_out.weight"] = lin(C, C); sd["proj_out.bias"] = t(C)
            twin = take(1, C, H, W)
            run = lambda: m(x, context=ctx)
        assert pos[0] == raw.size, f"dump has {raw.size} floats, consumed {pos[0]}"
        missing = set(m.state_dict().keys()) - set(sd.keys()); extra = set(sd.keys()) - set(m.state_dict().keys())
        assert not missing and not extra, (missing, extra)
        m.load_state_dict(sd, strict=True)
        out = run()
        err = (out - twin).abs()
        # the comparison must be able to fail: nudge one weight and re-run
        k0 = next(iter(sd)); key = [k for k in sd if sd[k].dim() >= 2][0]
        p = dict(m.named_parameters())[key]; old = p.data.clone(); p.data.view(-1)[0] += 1e-2
        out2 = run(); p.data.copy_(old)
        nudge = (out2 - twin).abs().max().item()
    res = {"kind": a.kind, "shape": sh, "elements": int(out.numel()),
           "max_abs_err_vs_twin": err.max().item(), "mean_abs_err_vs_twin": err.mean().item(),
           "max_abs_ref": out.abs().max().item(), "tol": a.tol, "within_tol": bool(err.max().item() <= a.tol),
           "nudged_weight": key, "nudged_max_abs_err": nudge, "nudge_detected": bool(nudge > a.tol),
           "torch": torch.__version__, "numpy": np.__version__, "threads": torch.get_num_threads()}
    print(json.dumps(res))
    sys.exit(0 if res["within_tol"] and res["nudge_detected"] else 1)

if __name__ == "__main__":
    main()
