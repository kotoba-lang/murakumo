#!/usr/bin/env python3
"""Local test fixture and oracle for safetensors_metal.kotoba.in (not a gate, not a route).
  python3 make_fixture.py write FILE        write a synthetic safetensors file (SDXL-style tensor names, mixed dtypes)
  python3 make_fixture.py expect FILE       print what the Kotoba program must print, from the safetensors header
                                            parsed by Python's json module, and the data-region checksum
Needs the safetensors and numpy packages (python3.11 with safetensors 0.4.4, numpy 1.26.4 were used)."""
import json, struct, sys
M48 = (1 << 48) - 1

def write(path):
    import numpy as np
    from safetensors.numpy import save_file
    rng = np.random.default_rng(7)
    t = {}
    for i in range(3):
        for j in range(4):
            t[f"model.diffusion_model.input_blocks.{i}.{j}.weight"] = rng.standard_normal((16 + 8 * j, 8, 3, 3)).astype(np.float16)
            t[f"model.diffusion_model.input_blocks.{i}.{j}.bias"] = rng.standard_normal((16 + 8 * j,)).astype(np.float16)
    t["model.diffusion_model.time_embed.0.weight"] = rng.standard_normal((8, 16)).astype(np.float32)
    t["first_stage_model.decoder.conv_in.weight"] = rng.standard_normal((12, 4, 3, 3)).astype(np.float16)
    t["cond_stage_model.logit_scale"] = np.array([4.5, 1.25], dtype=np.float32)
    t["a.scalar"] = np.array(3.5, dtype=np.float32)
    t["b.wide"] = rng.standard_normal((5, 7, 3)).astype(np.float64)
    t["c.ids"] = np.arange(10, dtype=np.int64)
    pad = (-sum(v.nbytes for v in t.values())) % 8
    if pad: t["z.pad"] = np.zeros((pad,), dtype=np.int8)
    save_file(t, path, metadata={"format": "pt", "note": "synthetic"})

def expect(path):
    b = open(path, "rb").read()
    n = struct.unpack("<Q", b[:8])[0]
    hdr = json.loads(b[8:8 + n])
    k = 0
    for name, ent in hdr.items():
        if name == "__metadata__": continue
        print(name, ent["dtype"], "x".join(str(d) for d in ent["shape"]), ent["data_offsets"][0], ent["data_offsets"][1]); k += 1
    print("tensors", k)
    data = b[8 + n:]
    assert len(data) % 8 == 0
    c = 0
    for (w,) in struct.iter_unpack("<Q", data):
        c = (c * 31 + w) & M48
    print("data-bytes", len(data), "checksum", c)

def corrupt_shape(src, dst):
    """copy src to dst with the first tensor's last dimension changed by one, offsets untouched"""
    b = open(src, "rb").read()
    n = struct.unpack("<Q", b[:8])[0]
    hdr = json.loads(b[8:8 + n])
    for name, ent in hdr.items():
        if name != "__metadata__":
            ent["shape"][-1] += 1; break
    h = json.dumps(hdr, separators=(",", ":")).encode()
    h += b" " * ((-len(h)) % 8)
    open(dst, "wb").write(struct.pack("<Q", len(h)) + h + b[8 + n:])

if __name__ == "__main__":
    if sys.argv[1] == "corrupt-shape": corrupt_shape(sys.argv[2], sys.argv[3])
    else: {"write": write, "expect": expect}[sys.argv[1]](sys.argv[2])
