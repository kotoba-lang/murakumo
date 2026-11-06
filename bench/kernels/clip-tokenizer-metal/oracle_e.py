#!/usr/bin/env python3
"""Local oracle for embedding words (not a gate, not a route): ComfyUI v0.44.0's own SDTokenizer.tokenize_with_weights with an embedding directory.
Per prompt and block, a line of 77 ids where an embedding row is printed as 1000000 + its row number (rows numbered in the order the embeddings are
first used, as the Kotoba program numbers them) and a line "w" + the 77 weights (fixed point).
  PYTHONPATH=<stubs>:<ComfyUI> python3 oracle_e.py <tokenizer dir> <prompts file> <embedding dir>"""
import sys, logging
logging.disable(logging.CRITICAL)
import torch
import comfy.sd1_clip as sc
reg = {}          # name -> first row
rows = [0]
store = {}        # data pointer of a loaded tensor -> (first row, columns)
orig = sc.load_embed
def wrapped(name, *a, **k):
    e = orig(name, *a, **k)
    if e is not None:
        if name not in reg:
            reg[name] = rows[0]; rows[0] += (1 if e.dim() == 1 else e.shape[0])
        store[e.untyped_storage().data_ptr()] = (reg[name], e.shape[-1])
    return e
sc.load_embed = wrapped
t = sc.SDTokenizer(tokenizer_path=sys.argv[1], max_length=77, pad_with_end=True, embedding_directory=sys.argv[3], embedding_size=768, embedding_key="clip_l",
                   has_start_token=True, pad_to_max_length=True)
def ident(x):
    if not torch.is_tensor(x): return x
    first, cols = store[x.untyped_storage().data_ptr()]
    return 1000000 + first + (x.storage_offset() // cols)
for line in open(sys.argv[2], encoding="utf-8").read().split("\n")[:-1]:
    for b in t.tokenize_with_weights(line):
        print(" ".join(str(ident(x[0])) for x in b))
        print("w " + " ".join(str(round(x[1] * 65536)) for x in b))
