#!/usr/bin/env python3
"""Copy a Qwen2 Q8_0 GGUF with the sign bit of one fp16 scale flipped (a negative control for run.sh, not a route).

  python3 flip_scale.py SRC.gguf DST.gguf [token]

The scale is the first Q8_0 block of `token`'s row (default 314, a prompt token and the first greedy token) in
token_embd.weight, which is both the embedding and the tied output head. Python 3 standard library only.
"""
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "gguf-metal"))
from make_fixture import parse  # noqa: E402


def main(src, dst, token="314"):
    _, _, tensors, dstart = parse(src)
    hit = [(dt, shape, off) for name, dt, shape, off in tensors if name == "token_embd.weight"]
    if len(hit) != 1 or hit[0][0] != 8:
        raise SystemExit("token_embd.weight is not one Q8_0 tensor")
    _, shape, off = hit[0]
    at = dstart + off + int(token) * (shape[0] // 32) * 34 + 1  # high byte of the fp16 scale
    shutil.copyfile(src, dst)
    with open(dst, "r+b") as fh:
        fh.seek(at)
        b = fh.read(1)[0]
        fh.seek(at)
        fh.write(bytes([b ^ 0x80]))
    print(f"flipped byte {at}: {b:#04x} -> {b ^ 0x80:#04x}")


if __name__ == "__main__":
    main(*sys.argv[1:])
