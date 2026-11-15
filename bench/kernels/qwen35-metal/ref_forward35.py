#!/usr/bin/env python3
"""Local reference forward pass for Qwen3.5 (GGUF architecture `qwen35`): a test-time oracle, not a gate, not a route.

  python3 ref_forward35.py FILE.gguf [--steps N] [--stats] [ids...]   greedy-continue the ids (default 760 6511 314 9338 369)

numpy only. Layer-major over the whole sequence; each greedy step reruns the sequence, so weights are dequantized one
layer at a time (memory stays near one layer). Qwen3.5 as in mlx-lm's qwen3_5.py / qwen3_next.py / gated_delta.py:
every 4th layer (3, 7, ...) is gated full attention (q|gate per head, q/k RMSNorm, NEOX RoPE on the first 64 of 256
dims, sigmoid output gate); the others are Gated DeltaNet (causal depthwise conv1d of width 4 + SiLU over q|k|v,
q and k RMS-normalised and scaled, decay g = exp(ssm_a * softplus(alpha + dt_bias)), beta = sigmoid(beta), a
128 x 128 state per value head, gated RMSNorm with silu(z)). SwiGLU MLP, tied output head.
Dequantization follows the GGUF block layouts of Q8_0, Q4_K, Q5_K and Q6_K.
"""
import struct
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "gguf-metal"))
from make_fixture import parse  # noqa: E402

QK = 256


def _scale_min_k4(s):  # s: [nb, 12] uint8 -> sc, m: [nb, 8]
    sc = np.empty((s.shape[0], 8), np.uint8)
    m = np.empty((s.shape[0], 8), np.uint8)
    sc[:, :4] = s[:, 0:4] & 63
    m[:, :4] = s[:, 4:8] & 63
    sc[:, 4:] = (s[:, 8:12] & 0xF) | ((s[:, 0:4] >> 6) << 4)
    m[:, 4:] = (s[:, 8:12] >> 4) | ((s[:, 4:8] >> 6) << 4)
    return sc.astype(np.float32), m.astype(np.float32)


def dequant(raw, dt, n):
    if dt == 0:
        return np.frombuffer(raw, "<f4", count=n).astype(np.float32)
    if dt == 8:
        b = np.frombuffer(raw, np.dtype([("d", "<f2"), ("q", "i1", 32)]), count=n // 32)
        return (b["d"].astype(np.float32)[:, None] * b["q"].astype(np.float32)).reshape(-1)
    nb = n // QK
    if dt == 12:  # Q4_K: d, dmin, scales[12], qs[128]
        b = np.frombuffer(raw, np.uint8, count=nb * 144).reshape(nb, 144)
        d = b[:, 0:2].copy().view("<f2").astype(np.float32)
        dm = b[:, 2:4].copy().view("<f2").astype(np.float32)
        sc, m = _scale_min_k4(b[:, 4:16])
        qs = b[:, 16:144].reshape(nb, 4, 32)
        q = np.stack([qs & 0xF, qs >> 4], axis=2).reshape(nb, 8, 32).astype(np.float32)
        return ((d[:, :, None] * sc[:, :, None]) * q - (dm[:, :, None] * m[:, :, None])).reshape(-1)
    if dt == 13:  # Q5_K: d, dmin, scales[12], qh[32], qs[128]
        b = np.frombuffer(raw, np.uint8, count=nb * 176).reshape(nb, 176)
        d = b[:, 0:2].copy().view("<f2").astype(np.float32)
        dm = b[:, 2:4].copy().view("<f2").astype(np.float32)
        sc, m = _scale_min_k4(b[:, 4:16])
        qh = b[:, 16:48]
        qs = b[:, 48:176].reshape(nb, 4, 32)
        out = np.empty((nb, 8, 32), np.float32)
        for j in range(4):
            out[:, 2 * j] = (qs[:, j] & 0xF) + 16 * ((qh >> (2 * j)) & 1)
            out[:, 2 * j + 1] = (qs[:, j] >> 4) + 16 * ((qh >> (2 * j + 1)) & 1)
        return ((d[:, :, None] * sc[:, :, None]) * out - (dm[:, :, None] * m[:, :, None])).reshape(-1)
    if dt == 14:  # Q6_K: ql[128], qh[64], scales[16] int8, d
        b = np.frombuffer(raw, np.uint8, count=nb * 210).reshape(nb, 210)
        ql = b[:, 0:128]
        qh = b[:, 128:192]
        sc = b[:, 192:208].view(np.int8).astype(np.float32)
        d = b[:, 208:210].copy().view("<f2").astype(np.float32)
        out = np.empty((nb, 256), np.float32)
        for h in range(2):
            l0 = ql[:, 64 * h:64 * h + 32].astype(np.int16)
            l1 = ql[:, 64 * h + 32:64 * h + 64].astype(np.int16)
            hh = qh[:, 32 * h:32 * h + 32].astype(np.int16)
            q1 = ((l0 & 0xF) | ((hh & 3) << 4)) - 32
            q2 = ((l1 & 0xF) | (((hh >> 2) & 3) << 4)) - 32
            q3 = ((l0 >> 4) | (((hh >> 4) & 3) << 4)) - 32
            q4 = ((l1 >> 4) | (((hh >> 6) & 3) << 4)) - 32
            s = sc[:, 8 * h:8 * h + 8]
            base = 128 * h
            for k, qq in enumerate((q1, q2, q3, q4)):
                for half in range(2):
                    sl = slice(16 * half, 16 * half + 16)
                    out[:, base + 32 * k + 16 * half:base + 32 * k + 16 * half + 16] = (
                        d * s[:, 2 * k + half:2 * k + half + 1] * qq[:, sl])
        return out.reshape(-1)
    raise ValueError(f"dtype {dt}")


class Model:
    def __init__(self, path):
        self.b, kvs, tensors, dstart = parse(path)
        self.kv = {k: v for k, t, v in kvs}
        self.raw = memoryview(self.b)[dstart:]
        self.t = {name: (dt, shape, off) for name, dt, shape, off in tensors}

    def get(self, name):
        dt, shape, off = self.t[name]
        n = int(np.prod(shape))
        return dequant(self.raw[off:], dt, n).reshape(tuple(reversed(shape)))

    def rows(self, name, lo, hi):  # rows lo..hi of a 2-D tensor
        dt, shape, off = self.t[name]
        rl = shape[0]
        bpr = {0: rl * 4, 8: rl // 32 * 34, 12: rl // 256 * 144, 13: rl // 256 * 176, 14: rl // 256 * 210}[dt]
        return dequant(self.raw[off + lo * bpr:], dt, (hi - lo) * rl).reshape(hi - lo, rl)


def f32(v):
    return struct.unpack("<f", struct.pack("<I", v))[0] if isinstance(v, int) else float(v)


def rms(x, w, eps):  # x [..., n]
    x64 = x.astype(np.float64)
    r = (x64 / np.sqrt(np.mean(x64 * x64, axis=-1, keepdims=True) + eps)).astype(np.float32)
    return r * w if w is not None else r


def silu(x):
    return x / (1.0 + np.exp(-x))


def forward(M, ids, stats=False, nl=None):
    kv = M.kv
    L = kv["qwen35.block_count"]
    D = kv["qwen35.embedding_length"]
    eps = f32(kv["qwen35.attention.layer_norm_rms_epsilon"])
    base = f32(kv["qwen35.rope.freq_base"])
    nh, nkv = kv["qwen35.attention.head_count"], kv["qwen35.attention.head_count_kv"]
    hd = kv["qwen35.attention.key_length"]
    rd = kv["qwen35.rope.dimension_count"]
    hk_n = kv["qwen35.ssm.group_count"]  # key heads of the delta net
    dk = kv["qwen35.ssm.state_size"]
    hv_n = kv["qwen35.ssm.time_step_rank"]  # value heads
    dv = kv["qwen35.ssm.inner_size"] // hv_n
    ck = kv["qwen35.ssm.conv_kernel"]
    T = len(ids)
    x = np.stack([M.rows("token_embd.weight", t, t + 1)[0] for t in ids])
    pos = np.arange(T)
    for l in range(L if nl is None else nl):
        p = f"blk.{l}."
        h = rms(x, M.get(p + "attn_norm.weight"), eps)
        if stats and l == 0:
            for nm in ("attn_norm.weight", "post_attention_norm.weight", "ssm_a", "ssm_norm.weight", "ssm_dt.bias"):
                a = M.get(p + nm)
                print(nm, "mean %.4f min %.4f max %.4f" % (a.mean(), a.min(), a.max()))
        if (l + 1) % kv["qwen35.full_attention_interval"] == 0:
            qg = (h @ M.get(p + "attn_q.weight").T).reshape(T, nh, 2 * hd)
            q, gate = qg[:, :, :hd], qg[:, :, hd:].reshape(T, nh * hd)
            k = (h @ M.get(p + "attn_k.weight").T).reshape(T, nkv, hd)
            v = (h @ M.get(p + "attn_v.weight").T).reshape(T, nkv, hd)
            q = rms(q, M.get(p + "attn_q_norm.weight"), eps)
            k = rms(k, M.get(p + "attn_k_norm.weight"), eps)
            half = rd // 2
            inv = base ** (-np.arange(half, dtype=np.float64) * 2 / rd)
            ang = pos[:, None] * inv[None, :]
            c, s = np.cos(ang).astype(np.float32)[:, None, :], np.sin(ang).astype(np.float32)[:, None, :]
            for z in (q, k):
                a_, b_ = z[..., :half].copy(), z[..., half:rd].copy()
                z[..., :half] = a_ * c - b_ * s
                z[..., half:rd] = a_ * s + b_ * c
            g = nh // nkv
            out = np.empty((T, nh, hd), np.float32)
            mask = np.triu(np.full((T, T), -np.inf, np.float32), 1)
            for hq in range(nh):
                sc = (q[:, hq] @ k[:, hq // g].T) / np.sqrt(hd) + mask
                w = np.exp(sc - sc.max(-1, keepdims=True))
                w /= w.sum(-1, keepdims=True)
                out[:, hq] = w @ v[:, hq // g]
            a = (out.reshape(T, -1) * (1 / (1 + np.exp(-gate)))) @ M.get(p + "attn_output.weight").T
        else:
            qkv = h @ M.get(p + "attn_qkv.weight").T  # [T, 2 hk dk + hv dv]
            z = (h @ M.get(p + "attn_gate.weight").T).reshape(T, hv_n, dv)
            al = h @ M.get(p + "ssm_alpha.weight").T  # [T, hv]
            be = h @ M.get(p + "ssm_beta.weight").T
            cw = M.get(p + "ssm_conv1d.weight")  # [channels, ck]
            pad = np.concatenate([np.zeros((ck - 1, qkv.shape[1]), np.float32), qkv])
            conv = sum(pad[i:i + T] * cw[:, i][None, :] for i in range(ck))
            conv = silu(conv)
            kd = hk_n * dk
            q = conv[:, :kd].reshape(T, hk_n, dk)
            k = conv[:, kd:2 * kd].reshape(T, hk_n, dk)
            v = conv[:, 2 * kd:].reshape(T, hv_n, dv)
            q = rms(q, None, 1e-6) * (1.0 / dk)
            k = rms(k, None, 1e-6) * (1.0 / np.sqrt(dk))
            A = M.get(p + "ssm_a")
            dtb = M.get(p + "ssm_dt.bias")
            sp = np.logaddexp(0.0, al + dtb).astype(np.float32)
            gdec = np.exp(A[None, :] * sp)  # ssm_a holds -exp(A_log)
            beta = 1 / (1 + np.exp(-be))
            rep = hv_n // hk_n
            S = np.zeros((hv_n, dv, dk), np.float32)
            y = np.empty((T, hv_n, dv), np.float32)
            for t in range(T):
                qt = np.tile(q[t], (rep, 1))
                kt = np.tile(k[t], (rep, 1))
                S = S * gdec[t][:, None, None]
                kvm = np.einsum("hvk,hk->hv", S, kt)
                delta = (v[t] - kvm) * beta[t][:, None]
                S = S + kt[:, None, :] * delta[:, :, None]
                y[t] = np.einsum("hvk,hk->hv", S, qt)
            yn = rms(y, M.get(p + "ssm_norm.weight"), eps) * silu(z)
            a = yn.reshape(T, -1) @ M.get(p + "ssm_out.weight").T
        x = x + a
        h = rms(x, M.get(p + "post_attention_norm.weight"), eps)
        f = (silu(h @ M.get(p + "ffn_gate.weight").T) * (h @ M.get(p + "ffn_up.weight").T)) @ M.get(p + "ffn_down.weight").T
        x = x + f
    hl = rms(x[-1:], M.get("output_norm.weight"), eps)[0]
    if nl is not None:
        print("x", " ".join("%.7g" % v for v in x[-1][:6]))
        print("h", " ".join("%.7g" % v for v in hl[:6]))
    V = M.t["token_embd.weight"][1][1]
    best, bv = -1, -np.inf
    top = []
    for lo in range(0, V, 16384):
        hi = min(V, lo + 16384)
        lg = M.rows("token_embd.weight", lo, hi) @ hl
        i = int(np.argmax(lg))
        top.extend(zip(lg, range(lo, hi)))
        if lg[i] > bv:
            best, bv = lo + i, float(lg[i])
    top.sort(reverse=True)
    return best, top[:3]


def main(argv):
    path, steps, stats, layers, rest = argv[0], 4, False, None, argv[1:]
    while rest and rest[0].startswith("--"):
        if rest[0] == "--steps":
            steps = int(rest[1]); rest = rest[2:]
        elif rest[0] == "--layers":
            layers = int(rest[1]); rest = rest[2:]
        elif rest[0] == "--stats":
            stats = True; rest = rest[1:]
    ids = [int(x) for x in rest] or [760, 6511, 314, 9338, 369]
    M = Model(path)
    out = []
    for s in range(steps):
        t, top = forward(M, ids + out, stats and s == 0, layers)
        print("step", s, "top", [(i, round(float(v), 3)) for v, i in top], flush=True)
        out.append(t)
    print("greedy", " ".join(map(str, out)))


if __name__ == "__main__":
    main(sys.argv[1:])
