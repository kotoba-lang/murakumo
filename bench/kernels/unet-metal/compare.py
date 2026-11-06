#!/usr/bin/env python3
"""Local comparison for unet_metal.kotoba.in (not a gate, not a route): splits the program's raw float32 dump into its stages (emb, h0, b1,
d, b3, m, o0, o1, o2, o3, final) and prints, per stage, the largest |gpu - ref| / max(1, |ref|) against make_fixture.py's expect_<stage>.f32.
  python3 compare.py <dump file> <fixture dir>"""
import sys, numpy as np
stages = [("emb", 128), ("h0", 32 * 256), ("b1", 32 * 256), ("d", 32 * 64), ("b3", 64 * 64), ("m", 64 * 64), ("o0", 64 * 64),
          ("o1", 64 * 256), ("o2", 32 * 256), ("o3", 32 * 256), ("final", 4 * 256)]
raw = np.fromfile(sys.argv[1], dtype=np.float32)
pos = 0
res = []
for name, n in stages:
    g = raw[pos:pos + n].astype(np.float64); pos += n
    r = np.fromfile(f"{sys.argv[2]}/expect_{name}.f32", dtype=np.float32).astype(np.float64)
    e = np.abs(g - r) / np.maximum(1.0, np.abs(r))
    res.append((name, float(e.max()), float(e.mean()), float(np.abs(r).max())))
print(" ".join(f"{n}={m:.2e}" for n, m, _, _ in res))
print("final max_err %.3e mean_err %.3e max_abs_ref %.3e" % (res[-1][1], res[-1][2], res[-1][3]))
