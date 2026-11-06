#!/usr/bin/env python3
"""Local comparison for clip_tower_metal.kotoba.in (not a gate, not a route): reads the GPU's raw float32 hidden states
(77 x 128) and the reference from make_fixture.py and prints the largest |gpu - ref| / max(1, |ref|) over all
elements, the same at the end-token row (the pooled output), and the largest |ref|.
  python3 compare.py <gpu.f32> <expect.f32> <expect_pooled.f32> <eos row>"""
import sys, numpy as np
g = np.fromfile(sys.argv[1], dtype=np.float32).astype(np.float64).reshape(77, 128)
r = np.fromfile(sys.argv[2], dtype=np.float32).astype(np.float64).reshape(77, 128)
pr = np.fromfile(sys.argv[3], dtype=np.float32).astype(np.float64)
eos = int(sys.argv[4])
e = np.abs(g - r) / np.maximum(1.0, np.abs(r))
ep = np.abs(g[eos] - pr) / np.maximum(1.0, np.abs(pr))
print(f"max_err {e.max():.3e} mean_err {e.mean():.3e} pooled_err {ep.max():.3e} max_abs_ref {np.abs(r).max():.3e}")
