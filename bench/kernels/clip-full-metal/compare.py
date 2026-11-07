"""usage: compare.py <gpu.err> <fixture dir> <H> <eos position>
Prints max |gpu - ref| / max(1, |ref|) for the last hidden state (77 x H), the penultimate hidden state, and the projected end-token embedding."""
import sys, numpy as np
raw = open(sys.argv[1], "rb").read(); d = sys.argv[2]; H = int(sys.argv[3]); eos = int(sys.argv[4]); n = 77 * H * 4
last = np.frombuffer(raw[:n], dtype=np.float32).reshape(77, H); pen = np.frombuffer(raw[n:2 * n], dtype=np.float32).reshape(77, H)
proj = np.frombuffer(raw[2 * n:3 * n], dtype=np.float32).reshape(77, H)[eos]
def err(a, b): return float(np.max(np.abs(a - b) / np.maximum(1.0, np.abs(b))))
rl = np.fromfile(d + "/expect_last.f32", dtype=np.float32).reshape(77, H); rp = np.fromfile(d + "/expect_pen.f32", dtype=np.float32).reshape(77, H)
rj = np.fromfile(d + "/expect_proj.f32", dtype=np.float32)
print("last=%.3e pen=%.3e proj=%.3e finite=%d  ref|last|max=%.2f |pen|max=%.2f" % (err(last, rl), err(pen, rp), err(proj, rj),
      int(np.isfinite(last).all() and np.isfinite(pen).all() and np.isfinite(proj).all()), np.abs(rl).max(), np.abs(rp).max()))
