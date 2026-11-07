"""Oracle for sampler_metal.kotoba.in: ComfyUI v0.44.0's own ModelSamplingDiscrete (scaled-linear betas), simple_scheduler and
k-diffusion sample_euler, float32 on CPU, driving the same toy eps functions with the same classifier-free guidance on denoised values.
usage: compare.py <gpu.err> <comfy-src> <N> <steps>   (needs torch, numpy; stubs on PYTHONPATH). Prints the largest
|gpu - ref| / max|ref| for the sigma table, the schedule and the final latent."""
import sys, numpy as np, torch
sys.path.insert(0, sys.argv[2])
import types
sys.modules.setdefault('torchsde', types.ModuleType('torchsde'))  # only the stochastic samplers use it; sample_euler does not
import comfy.model_sampling as ms
import ast
from tqdm import trange
raw = open(sys.argv[1], "rb").read(); N = int(sys.argv[3]); steps = int(sys.argv[4])
st = np.frombuffer(raw[:4000], dtype=np.float32); sg = np.frombuffer(raw[4000:4128], dtype=np.float32)[:steps + 1]
xg = np.frombuffer(raw[4128:4128 + 4 * N], dtype=np.float32)
class Cfg: sampling_settings = {"beta_schedule": "linear", "linear_start": 0.00085, "linear_end": 0.012, "timesteps": 1000}
msd = ms.ModelSamplingDiscrete(Cfg)
# comfy.samplers pulls in the whole text-encoder stack; take the real simple_scheduler source text out of samplers.py instead
_src = open(sys.argv[2] + "/comfy/samplers.py").read()
_fn = [n for n in ast.parse(_src).body if isinstance(n, ast.FunctionDef) and n.name == "simple_scheduler"][0]
_ns = {"torch": torch}; exec(ast.get_source_segment(_src, _fn), _ns)
sigmas = _ns["simple_scheduler"](msd, steps)
def lcg(n, seed=12345):
    out = []; s = seed
    for _ in range(n):
        s = (s * 1103515245 + 12345) & 0x7fffffff; out.append(((s >> 8) & 127) - 64)
    return np.array(out, dtype=np.float32)
x0 = torch.from_numpy((lcg(N) / np.float32(64.0)) * np.float32(14.0)).reshape(1, N)
# comfy.k_diffusion.sampling imports the model-patcher stack; take the real sample_euler / to_d / append_dims source text instead
def _grab(path, names):
    src = open(sys.argv[2] + path).read(); out = {}
    for n in ast.parse(src).body:
        if isinstance(n, ast.FunctionDef) and n.name in names: out[n.name] = ast.get_source_segment(src, n)
    return out
_kd = _grab("/comfy/k_diffusion/sampling.py", {"to_d", "sample_euler"}); _ut = _grab("/comfy/k_diffusion/utils.py", {"append_dims"})
class _U: pass
_u = _U(); _uns = {"torch": torch}; exec(_ut["append_dims"], _uns); _u.append_dims = _uns["append_dims"]
_kns = {"torch": torch, "trange": trange, "utils": _u}
exec(_kd["to_d"], _kns); exec("no_grad = torch.no_grad()\n" + _kd["sample_euler"], _kns)
class kds: sample_euler = staticmethod(lambda *a, **k: _kns["sample_euler"](*a, **k))
def model(x, sigma, **kw):
    sigma = sigma.view(-1, 1)
    xin = x / torch.sqrt(sigma ** 2 + 1.0)
    eu = 0.5 * xin; ec = 0.5 * xin + (0.25 * xin) / (1.0 + xin * xin)
    du = x - sigma * eu; dc = x - sigma * ec
    return du + (dc - du) * 7.5
xr = kds.sample_euler(model, x0.clone(), sigmas, disable=True).numpy().reshape(-1)
def err(a, b): return float(np.max(np.abs(a - b)) / max(1e-30, float(np.max(np.abs(b)))))   # relative to the reference's largest value
tab = msd.sigmas.numpy()
print("sigma_table=%.3e schedule=%.3e final=%.3e" % (err(st, tab), err(sg, sigmas.numpy()), err(xg, xr)))
print("ref |x|max=%.3f sigma0=%.3f" % (float(np.abs(xr).max()), float(sigmas[0])))
