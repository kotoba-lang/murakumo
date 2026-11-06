"""usage: compare.py <gpu.err> <fixture dir> <LAT> <STEPS>
Prints the largest |gpu - ref| / max(1, |ref|) for the latent after each Euler step and for the decoded image."""
import sys, numpy as np
raw = open(sys.argv[1], "rb").read(); d = sys.argv[2]; lat = int(sys.argv[3]); steps = int(sys.argv[4])
n = 4 * lat * lat; ni = 3 * (8 * lat) ** 2
out = []; off = 0; fin = True
for name, cnt in (("ctx_c", 77 * 2048), ("y_c", 2816), ("ctx_u", 77 * 2048), ("y_u", 2816)):   # the conditioning the program built on the GPU
    g = np.frombuffer(raw[off:off + 4 * cnt], dtype=np.float32); off += 4 * cnt
    r = np.fromfile("%s/%s.f32" % (d, name), dtype=np.float32)
    fin = fin and bool(np.isfinite(g).all()); out.append((name, float(np.max(np.abs(g - r) / np.maximum(1.0, np.abs(r))))))
for i in range(steps):
    g = np.frombuffer(raw[off:off + 4 * n], dtype=np.float32); off += 4 * n
    r = np.fromfile("%s/expect_step%d.f32" % (d, i), dtype=np.float32)
    fin = fin and bool(np.isfinite(g).all()); out.append(("step%d" % i, float(np.max(np.abs(g - r) / np.maximum(1.0, np.abs(r))))))
g = np.frombuffer(raw[off:off + 4 * ni], dtype=np.float32); r = np.fromfile(d + "/expect_image.f32", dtype=np.float32)
fin = fin and bool(np.isfinite(g).all()); out.append(("image", float(np.max(np.abs(g - r) / np.maximum(1.0, np.abs(r))))))
print(" ".join("%s=%.3e" % (k, v) for k, v in out) + "  finite=%d  ref|x_final|max=%.2f ref|image|max=%.2f" % (int(fin), np.abs(np.fromfile("%s/expect_step%d.f32" % (d, steps - 1), dtype=np.float32)).max(), np.abs(r).max()))
