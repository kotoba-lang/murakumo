#!/usr/bin/env python3
"""Local reference for the DFlash drafter (numpy): a test-time oracle, not a gate, not a route.
  python3 ref_dflash.py MODEL.gguf DFLASH.safetensors CT.bin TOK
The drafter as z-lab/dflash's model.py (@ 07ebd93) runs it: fc and hidden_norm over the context rows; per layer q from
the block, k and v from the context rows and the block, q/k RMSNorm per head, NEOX RoPE (base 1e7) at the context's
positions and the block's; attention over the context and the block, causal within the block in the sliding-window
layers (0 .. 4); SwiGLU MLP; final norm; the main model's tied head.
CT.bin: n rows of 8 x 2560 float32 (the main model's residual after layers 1, 5, .., 29 at positions 0 .. n-1).
Prints the 15 drafts (argmax of the tied head over block rows 1 .. 15)."""
import json, struct, sys
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
import ref_forward35 as rf

gg, st, ctp, tok = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
M = rf.Model(gg)
f = open(st, "rb"); hl = struct.unpack("<Q", f.read(8))[0]; H = json.loads(f.read(hl)); base = 8 + hl
def T(name):
    v = H[name]; a, b = v["data_offsets"]; f.seek(base + a)
    u = np.frombuffer(f.read(b - a), dtype=np.uint16).astype(np.uint32) << 16
    return u.view(np.float32).reshape(v["shape"])
ct = np.fromfile(ctp, dtype=np.float32).reshape(-1, 20480)
n = ct.shape[0]
eps = 1e-6
def rms(x, w): return rf.rms(x, w, eps)
ctx = rms(ct @ T("fc.weight").T, T("hidden_norm.weight"))
emb = lambda t: M.rows("token_embd.weight", t, t + 1)[0]
x = np.stack([emb(tok)] + [emb(248077)] * 15).astype(np.float32)
pos_c = np.arange(n); pos_b = np.arange(n, n + 16)
def rope(v, pos):  # v [T, H, 128], NEOX over the whole head
    inv = 1e7 ** (-np.arange(0, 128, 2) / 128.0)
    ang = pos[:, None] * inv[None, :]
    c, s = np.cos(ang)[:, None, :], np.sin(ang)[:, None, :]
    a, b = v[..., :64], v[..., 64:]
    return np.concatenate([a * c - b * s, b * c + a * s], -1)
for l in range(6):
    p = "layers.%d." % l
    h = rms(x, T(p + "input_layernorm.weight"))
    q = (h @ T(p + "self_attn.q_proj.weight").T).reshape(16, 32, 128)
    kn = (h @ T(p + "self_attn.k_proj.weight").T).reshape(16, 8, 128)
    vn = (h @ T(p + "self_attn.v_proj.weight").T).reshape(16, 8, 128)
    kc = (ctx @ T(p + "self_attn.k_proj.weight").T).reshape(n, 8, 128)
    vc = (ctx @ T(p + "self_attn.v_proj.weight").T).reshape(n, 8, 128)
    q = rope(rms(q, T(p + "self_attn.q_norm.weight")), pos_b)
    k = np.concatenate([rope(rms(kc, T(p + "self_attn.k_norm.weight")), pos_c), rope(rms(kn, T(p + "self_attn.k_norm.weight")), pos_b)])
    v = np.concatenate([vc, vn])
    o = np.zeros((16, 32, 128), np.float32)
    for hh in range(32):
        sc = q[:, hh] @ k[:, hh // 4].T / np.sqrt(128.0)
        if l < 5:  # sliding-window layers: causal within the block
            sc[:, n:] = np.where(np.arange(16)[None, :] <= np.arange(16)[:, None], sc[:, n:], -np.inf)
        sc = np.exp(sc - sc.max(-1, keepdims=True)); sc /= sc.sum(-1, keepdims=True)
        o[:, hh] = sc @ v[:, hh // 4]
    x = x + o.reshape(16, 4096) @ T(p + "self_attn.o_proj.weight").T
    h = rms(x, T(p + "post_attention_layernorm.weight"))
    x = x + (rf.silu(h @ T(p + "mlp.gate_proj.weight").T) * (h @ T(p + "mlp.up_proj.weight").T)) @ T(p + "mlp.down_proj.weight").T
hn = rms(x, T("norm.weight"))[1:]
V = 248320; best = np.full(15, -np.inf); arg = np.zeros(15, int)
for lo in range(0, V, 16384):
    hi = min(V, lo + 16384); lg = hn @ M.rows("token_embd.weight", lo, hi).T
    i = lg.argmax(1); m = lg[np.arange(15), i]; up = m > best; best[up] = m[up]; arg[up] = lo + i[up]
print("drafts", " ".join(map(str, arg)))
