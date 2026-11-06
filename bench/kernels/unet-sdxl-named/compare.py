"""usage: compare.py <gpu.err> <fixture dir> <MC> <LAT>
Prints the largest |gpu - ref| / max(1, |ref|) at each stage of the SDXL-topology UNet (emb, input blocks h0..h8, middle m, output blocks o0..o8) and the final output."""
import sys, numpy as np
raw = open(sys.argv[1], "rb").read(); d = sys.argv[2]; mc = int(sys.argv[3]); lat = int(sys.argv[4])
c0, c1, c2 = mc, 2 * mc, 4 * mc; hw0 = lat * lat; hw1 = hw0 // 4; hw2 = hw0 // 16
sizes = [("emb", 4 * mc), ("h0", c0 * hw0), ("h1", c0 * hw0), ("h2", c0 * hw0), ("h3", c0 * hw1), ("h4", c1 * hw1), ("h5", c1 * hw1), ("h6", c1 * hw2),
         ("h7", c2 * hw2), ("h8", c2 * hw2), ("m", c2 * hw2), ("o0", c2 * hw2), ("o1", c2 * hw2), ("o2", c2 * hw1), ("o3", c1 * hw1), ("o4", c1 * hw1),
         ("o5", c1 * hw0), ("o6", c0 * hw0), ("o7", c0 * hw0), ("o8", c0 * hw0), ("final", 4 * hw0)]
off = 0; res = []; fin = True
for name, n in sizes:
    g = np.frombuffer(raw[off:off + 4 * n], dtype=np.float32); off += 4 * n
    r = np.fromfile("%s/expect_%s.f32" % (d, name), dtype=np.float32)
    assert g.size == r.size, (name, g.size, r.size)
    fin = fin and bool(np.isfinite(g).all())
    res.append((name, float(np.max(np.abs(g - r) / np.maximum(1.0, np.abs(r))))))
print(" ".join("%s=%.2e" % (n, e) for n, e in res) + "  finite=%d" % int(fin))
