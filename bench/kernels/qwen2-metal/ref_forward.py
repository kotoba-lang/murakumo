#!/usr/bin/env python3
"""Local reference forward pass for Qwen2.5-0.5B Q8_0 GGUF (a test-time oracle, not a gate, not a route).

  python3 ref_forward.py FILE.gguf [ids...]        greedy-continue the ids (default 760 6511 314 9338 369, 4 steps)
  python3 ref_forward.py FILE.gguf --dump N ids..   also print the first N values of the hidden state after each stage
                                                   of the LAST prompt position (embedding, layer-0 attn_norm, q, k, v,
                                                   attn out, ffn out, final norm, logits)

numpy only. Layout follows llama.cpp: GGUF dims are ne0 first, so a weight `896x4864` has 4864 rows of 896 inputs;
Q8_0 blocks are fp16 scale + 32 int8. Qwen2 in llama.cpp uses NEOX RoPE (pairs i and i + d/2), q/k/v biases, no
o-proj bias, SwiGLU, tied output head (no output.weight), RMSNorm with the GGUF epsilon.
"""
import struct
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "gguf-metal"))
from make_fixture import parse  # noqa: E402


def load(path):
    b, kvs, tensors, dstart = parse(path)
    kv = {k: v for k, t, v in kvs}
    raw = memoryview(b)[dstart:]
    T = {}
    for name, dt, shape, off in tensors:
        n = int(np.prod(shape))
        if dt == 0:
            a = np.frombuffer(raw, dtype="<f4", count=n, offset=off).astype(np.float32)
        elif dt == 8:
            blocks = np.frombuffer(raw, dtype=np.dtype([("d", "<f2"), ("q", "i1", 32)]), count=n // 32, offset=off)
            a = (blocks["d"].astype(np.float32)[:, None] * blocks["q"].astype(np.float32)).reshape(-1)
        else:
            raise ValueError(f"dtype {dt} for {name}")
        T[name] = a.reshape(tuple(reversed(shape)))  # rows = last dim, row length = ne0
    return kv, T


def f32_of_bits(v):
    return struct.unpack("<f", struct.pack("<I", v))[0] if isinstance(v, int) else float(v)


def rms(x, w, eps):
    return (x / np.sqrt(np.mean(x.astype(np.float64) ** 2) + eps)).astype(np.float32) * w


def rope(x, pos, base):  # x: [heads, d], NEOX pairing
    d = x.shape[-1]
    half = d // 2
    inv = base ** (-np.arange(half, dtype=np.float64) * 2 / d)
    ang = pos * inv
    c, s = np.cos(ang).astype(np.float32), np.sin(ang).astype(np.float32)
    a, b = x[:, :half], x[:, half:]
    return np.concatenate([a * c - b * s, a * s + b * c], axis=-1)


def main(argv):
    path = argv[0]
    dump = 0
    rest = argv[1:]
    if rest[:1] == ["--dump"]:
        dump = int(rest[1])
        rest = rest[2:]
    ids = [int(x) for x in rest] or [760, 6511, 314, 9338, 369]
    kv, T = load(path)
    L = kv["qwen2.block_count"]
    nh, nkv = kv["qwen2.attention.head_count"], kv["qwen2.attention.head_count_kv"]
    D = kv["qwen2.embedding_length"]
    hd = D // nh
    eps = f32_of_bits(kv["qwen2.attention.layer_norm_rms_epsilon"])
    base = f32_of_bits(kv["qwen2.rope.freq_base"])
    emb = T["token_embd.weight"]
    K = [[] for _ in range(L)]
    V = [[] for _ in range(L)]

    def show(tag, x, last):
        if dump and last:
            print(tag, " ".join(f"{v:.6g}" for v in np.asarray(x).reshape(-1)[:dump]))

    def step(tok, pos, last):
        x = emb[tok].copy()
        show("emb", x, last)
        for l in range(L):
            p = f"blk.{l}."
            h = rms(x, T[p + "attn_norm.weight"], eps)
            q = T[p + "attn_q.weight"] @ h + T[p + "attn_q.bias"]
            k = T[p + "attn_k.weight"] @ h + T[p + "attn_k.bias"]
            v = T[p + "attn_v.weight"] @ h + T[p + "attn_v.bias"]
            if l == 0:
                show("l0.norm", h, last); show("l0.q", q, last); show("l0.k", k, last); show("l0.v", v, last)
            q = rope(q.reshape(nh, hd), pos, base)
            k = rope(k.reshape(nkv, hd), pos, base)
            K[l].append(k)
            V[l].append(v.reshape(nkv, hd))
            Ks, Vs = np.stack(K[l]), np.stack(V[l])  # [t, nkv, hd]
            out = np.empty((nh, hd), np.float32)
            g = nh // nkv
            for hq in range(nh):
                kh = hq // g
                sc = (Ks[:, kh, :] @ q[hq]) / np.sqrt(hd)
                w = np.exp(sc - sc.max()); w /= w.sum()
                out[hq] = w @ Vs[:, kh, :]
            a = T[p + "attn_output.weight"] @ out.reshape(-1)
            x = x + a
            h = rms(x, T[p + "ffn_norm.weight"], eps)
            gt = T[p + "ffn_gate.weight"] @ h
            up = T[p + "ffn_up.weight"] @ h
            f = T[p + "ffn_down.weight"] @ ((gt / (1 + np.exp(-gt))) * up)
            if l == 0:
                show("l0.attn", a, last); show("l0.ffn", f, last)
            x = x + f
        h = rms(x, T["output_norm.weight"], eps)
        show("final", h, last)
        logits = emb @ h
        show("logits", logits, last)
        return int(np.argmax(logits)), float(logits.max())

    pos = 0
    for i, t in enumerate(ids):
        nxt, top = step(t, pos, i == len(ids) - 1)
        pos += 1
    out = [nxt]
    for _ in range(3):
        nxt, top = step(nxt, pos, False)
        pos += 1
        out.append(nxt)
    print("greedy", " ".join(map(str, out)))


if __name__ == "__main__":
    main(sys.argv[1:])
