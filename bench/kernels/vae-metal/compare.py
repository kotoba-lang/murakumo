"""usage: compare.py <gpu.err> <fixture dir> <CH> <L> <taps 0|1>
Prints max |gpu - ref| / max(1, |ref|) per stage (conv_in, mid, up levels 3..1, last block, final image) from the raw dump of vae_metal."""
import sys, numpy as np
raw = open(sys.argv[1], "rb").read(); d = sys.argv[2]; CH = int(sys.argv[3]); L = int(sys.argv[4]); taps = int(sys.argv[5])
CM = CH * 4
stages = []
if taps:
    stages = [("cin", CM * L * L), ("mid", CM * L * L), ("l3", CM * 4 * L * L), ("l2", CM * 16 * L * L), ("l1", 2 * CH * 64 * L * L), ("b0", CH * 64 * L * L)]
stages.append(("final", 3 * 64 * L * L))
off = 0; out = []
for name, n in stages:
    g = np.frombuffer(raw[off:off + 4 * n], dtype=np.float32); off += 4 * n
    r = np.fromfile("%s/expect_%s.f32" % (d, name), dtype=np.float32)
    assert g.size == r.size, (name, g.size, r.size)
    out.append((name, float(np.max(np.abs(g - r) / np.maximum(1.0, np.abs(r)))), bool(np.isfinite(g).all()), float(np.abs(r).max()), float(np.max(np.abs(g - r)) / max(1e-30, np.abs(r).max()))))
print(" ".join("%s=%.3e" % (n, e) for n, e, _, _, _ in out) + "  finite=%d  ref|final|max=%.2f" % (int(all(f for _, _, f, _, _ in out)), out[-1][3]) +
      " " + " ".join("rel_%s=%.3e" % (n, rl) for n, _, _, _, rl in out) + "  refmax=%.1f" % max(m for _, _, _, m, _ in out))
