#!/usr/bin/env python3
"""Local test fixture and oracle for gguf_metal.kotoba.in (not a gate, not a route).
  python3 make_fixture.py write FILE        write a synthetic GGUF v3 file (E1 tensor names,
                                            scaled-down shapes, F32 + Q8_0 only)
  python3 make_fixture.py write-big FILE    the same file plus one 20 MiB F32 tensor (pad.weight), so the
                                            file is over 16 MiB and the data region is read in chunks
  python3 make_fixture.py expect FILE       print what the Kotoba program must print, from an
                                            independent re-parse of the file
  python3 make_fixture.py corrupt-data SRC DST
                                            copy SRC to DST with one tensor-data byte flipped
  python3 make_fixture.py corrupt-shape SRC DST
                                            copy SRC to DST with the first tensor's last dim + 1
  python3 make_fixture.py corrupt-magic SRC DST
                                            copy SRC to DST with the first magic byte flipped
Needs only the Python 3 standard library. Layout follows llama.cpp ggml/include/gguf.h
(magic u32, version u32, n_tensors i64, n_kv i64, KVs, tensor infos, aligned data blob)
and ggml.h dtype ids (F32=0, Q8_0=8; Q8_0 block = fp16 scale + 32 int8 = 34 bytes).
"""
import random, struct, sys

ALIGN = 32
M48 = (1 << 48) - 1
# (name, dtype_id, shape) — E1 inventory names, scaled-down shapes; 2 + 24*12 = 290 tensors
LAYERS = range(24)
TENSORS = [("token_embd.weight", 8, (64, 128)), ("output_norm.weight", 0, (64,))]
for L in LAYERS:
    p = f"blk.{L}."
    TENSORS += [
        (p + "attn_q.weight", 8, (64, 64)), (p + "attn_q.bias", 0, (64,)),
        (p + "attn_k.weight", 8, (64, 16)), (p + "attn_k.bias", 0, (16,)),
        (p + "attn_v.weight", 8, (64, 16)), (p + "attn_v.bias", 0, (16,)),
        (p + "attn_output.weight", 8, (64, 64)), (p + "attn_norm.weight", 0, (64,)),
        (p + "ffn_gate.weight", 8, (64, 128)), (p + "ffn_up.weight", 8, (64, 128)),
        (p + "ffn_down.weight", 8, (128, 64)), (p + "ffn_norm.weight", 0, (64,)),
    ]
NTOK, NMRG = 151936, 151387  # pinned E1 tokenizer lengths
KVS = [  # (key, type_id, value); type ids per gguf.h: 4=u32 6=f32 7=bool 8=string 9=array
    ("general.architecture", 8, "qwen2"),
    ("general.file_type", 4, 7),
    ("general.alignment", 4, ALIGN),
    ("qwen2.block_count", 4, 24),
    ("qwen2.embedding_length", 4, 896),
    ("qwen2.feed_forward_length", 4, 4864),
    ("qwen2.attention.head_count", 4, 14),
    ("qwen2.attention.head_count_kv", 4, 2),
    ("qwen2.rope.freq_base", 6, 1000000.0),
    ("qwen2.attention.layer_norm_rms_epsilon", 6, 1e-6),
    ("tokenizer.ggml.model", 8, "gpt2"),
    ("tokenizer.ggml.pre", 8, "qwen2"),
    ("tokenizer.ggml.tokens", 9, (8, [f"tok{i}" for i in range(NTOK)])),
    ("tokenizer.ggml.merges", 9, (8, [f"a{i} b{i}" for i in range(NMRG)])),
    ("tokenizer.ggml.eos_token_id", 4, 151645),
    ("tokenizer.ggml.bos_token_id", 4, 151643),
    ("tokenizer.ggml.padding_token_id", 4, 151643),
    ("tokenizer.ggml.add_bos_token", 7, 0),
]


def _str(b, s):
    e = s.encode()
    b += struct.pack("<Q", len(e)) + e


def _val(b, t, v):
    if t in (0, 1, 7):
        b += struct.pack("<B", v)
    elif t in (2, 3):
        b += struct.pack("<H", v)
    elif t in (4, 5):
        b += struct.pack("<I", v)
    elif t == 6:
        b += struct.pack("<f", v)
    elif t in (10, 11):
        b += struct.pack("<Q", v)
    elif t == 12:
        b += struct.pack("<d", v)
    elif t == 8:
        _str(b, v)
    elif t == 9:
        et, items = v
        b += struct.pack("<i", et) + struct.pack("<Q", len(items))
        for it in items:
            if et == 8:
                _str(b, it)
            else:
                _val(b, et, it)
    else:
        raise ValueError(t)


def _q8_0(rng, n):
    xs = [rng.uniform(-1, 1) for _ in range(n)]
    out = bytearray()
    for i in range(0, n, 32):
        blk = xs[i:i + 32]
        amax = max(abs(x) for x in blk) or 1.0
        d = amax / 127.0
        out += struct.pack("<e", d)
        for x in blk:
            q = int(round(x / d))
            out += struct.pack("<b", max(-128, min(127, q)))
    return bytes(out)


def _f32(rng, n):
    return struct.pack(f"<{n}f", *[rng.uniform(-1, 1) for _ in range(n)])


def write(path, big=False):
    rng = random.Random(7)
    tensors = TENSORS + ([("pad.weight", 0, (5 * 1024 * 1024,))] if big else [])
    hdr = bytearray(b"GGUF" + struct.pack("<I", 3) + struct.pack("<q", len(tensors)) + struct.pack("<q", len(KVS)))
    for k, t, v in KVS:
        _str(hdr, k)
        hdr += struct.pack("<i", t)
        _val(hdr, t, v)
    data, offs = bytearray(), []
    off = 0
    infos = bytearray()
    for name, dt, shape in tensors:
        n = 1
        for d in shape:
            n *= d
        raw = _f32(rng, n) if dt == 0 else _q8_0(rng, n)
        if off % ALIGN:
            pad = ALIGN - off % ALIGN
            data += b"\x00" * pad
            off += pad
        _str(infos, name)
        infos += struct.pack("<I", len(shape))
        for d in shape:
            infos += struct.pack("<q", d)
        infos += struct.pack("<i", dt) + struct.pack("<Q", off)
        offs.append((off, len(raw)))
        data += raw
        off += len(raw)
    hdr += infos
    pos = len(hdr)
    if pos % ALIGN:
        hdr += b"\x00" * (ALIGN - pos % ALIGN)
    open(path, "wb").write(bytes(hdr) + bytes(data))


def _rd(b, pos, fmt):
    n = struct.calcsize(fmt)
    if pos + n > len(b):
        raise ValueError("truncated")
    return struct.unpack_from(fmt, b, pos)[0], pos + n


def parse(path):
    b = open(path, "rb").read()
    if len(b) < 24 or b[:4] != b"GGUF":
        raise ValueError("bad magic")
    ver, pos = _rd(b, 4, "<I")
    if ver != 3:
        raise ValueError("bad version")
    nt, pos = _rd(b, 8, "<q")
    nk, pos = _rd(b, 16, "<q")
    kvs = []
    for _ in range(nk):
        ln, pos = _rd(b, pos, "<Q")
        if pos + ln > len(b):
            raise ValueError("truncated")
        k = b[pos:pos + ln].decode()
        pos += ln
        t, pos = _rd(b, pos, "<i")
        if t in (0, 1, 7):
            v, pos = _rd(b, pos, "<B")
        elif t in (2, 3):
            v, pos = _rd(b, pos, "<H")
        elif t in (4, 5):
            v, pos = _rd(b, pos, "<I")
        elif t == 6:
            v, pos = _rd(b, pos, "<I")  # raw f32 bits; exact across languages
        elif t in (10, 11):
            v, pos = _rd(b, pos, "<Q")
        elif t == 12:
            v, pos = _rd(b, pos, "<d")
        elif t == 8:
            ln, pos = _rd(b, pos, "<Q")
            if pos + ln > len(b):
                raise ValueError("truncated")
            v = b[pos:pos + ln].decode()
            pos += ln
        elif t == 9:
            et, pos = _rd(b, pos, "<i")
            cn, pos = _rd(b, pos, "<Q")
            if et == 8:
                for _ in range(cn):
                    ln, pos = _rd(b, pos, "<Q")
                    if pos + ln > len(b):
                        raise ValueError("truncated")
                    pos += ln
                v = (et, cn)
            else:
                sz = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}[et]
                if pos + cn * sz > len(b):
                    raise ValueError("truncated")
                pos += cn * sz
                v = (et, cn)
        else:
            raise ValueError("bad kv type")
        kvs.append((k, t, v))
    tensors = []
    for _ in range(nt):
        ln, pos = _rd(b, pos, "<Q")
        if pos + ln > len(b):
            raise ValueError("truncated")
        name = b[pos:pos + ln].decode()
        pos += ln
        nd, pos = _rd(b, pos, "<I")
        shape = []
        for _ in range(nd):
            d, pos = _rd(b, pos, "<q")
            shape.append(d)
        dt, pos = _rd(b, pos, "<i")
        off, pos = _rd(b, pos, "<Q")
        tensors.append((name, dt, shape, off))
    dstart = pos + (-pos % ALIGN)
    return b, kvs, tensors, dstart


def expect(path):
    b, kvs, tensors, dstart = parse(path)
    data = b[dstart:]
    for k, t, v in kvs:
        if t == 9:
            print("kv", k, t, f"{v[0]}:{v[1]}")
        else:
            print("kv", k, t, v)
    print("kvs", len(kvs))
    maxend, prev = 0, 0
    for name, dt, shape, off in tensors:
        n = 1
        for d in shape:
            n *= d
        size = n * 4 if dt == 0 else (n // 32) * 34 if dt == 8 and n % 32 == 0 else None
        if size is None or off < 0 or off % ALIGN or off < prev or off + size > len(data):
            raise ValueError("bad tensor size/offset")
        maxend = max(maxend, off + size)
        prev = off + size
        print(name, dt, "x".join(str(d) for d in shape), off, off + size)
    if maxend != len(data):
        raise ValueError("bad tensor offsets")
    print("tensors", len(tensors))
    if len(data) % 8:
        raise ValueError("data region not a multiple of 8")
    c = 0
    for (w,) in struct.iter_unpack("<Q", data):
        c = (c * 31 + w) & M48
    print("data-bytes", len(data), "checksum", c)


def corrupt_data(src, dst):
    b = bytearray(open(src, "rb").read())
    _, _, tensors, dstart = parse(src)
    b[dstart + tensors[0][3]] ^= 1
    open(dst, "wb").write(bytes(b))


def corrupt_shape(src, dst):
    b = bytearray(open(src, "rb").read())
    _, _, _, dstart = parse(src)
    # first tensor info starts right after the KVs; its name-len sits at the
    # tensor-info start: walk is complex, so find the first dim field by
    # re-encoding: dims of the first tensor live after name+ndims. Simplest
    # correct edit: parse to get the offset of the first dim, then bump it.
    pos = 24
    nk = struct.unpack_from("<q", b, 16)[0]
    for _ in range(nk):  # skip KVs to reach tensor infos
        ln = struct.unpack_from("<Q", b, pos)[0]
        pos += 8 + ln
        t = struct.unpack_from("<i", b, pos)[0]
        pos += 4
        if t in (0, 1, 7):
            pos += 1
        elif t in (2, 3):
            pos += 2
        elif t in (4, 5, 6):
            pos += 4
        elif t in (10, 11, 12):
            pos += 8
        elif t == 8:
            ln = struct.unpack_from("<Q", b, pos)[0]
            pos += 8 + ln
        elif t == 9:
            et = struct.unpack_from("<i", b, pos)[0]
            cn = struct.unpack_from("<Q", b, pos + 4)[0]
            pos += 12
            if et == 8:
                for _ in range(cn):
                    ln = struct.unpack_from("<Q", b, pos)[0]
                    pos += 8 + ln
            else:
                pos += cn * {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}[et]
    ln = struct.unpack_from("<Q", b, pos)[0]
    pos += 8 + ln
    nd = struct.unpack_from("<I", b, pos)[0]
    pos += 4
    d0 = struct.unpack_from("<q", b, pos)[0]
    struct.pack_into("<q", b, pos, d0 + 1)  # bump first dim, offsets untouched
    open(dst, "wb").write(bytes(b))


def corrupt_magic(src, dst):
    b = bytearray(open(src, "rb").read())
    b[0] ^= 1
    open(dst, "wb").write(bytes(b))


if __name__ == "__main__":
    {"write": write, "write-big": lambda path: write(path, True), "expect": expect, "corrupt-data": corrupt_data,
     "corrupt-shape": corrupt_shape, "corrupt-magic": corrupt_magic}[sys.argv[1]](sys.argv[2], *sys.argv[3:])
