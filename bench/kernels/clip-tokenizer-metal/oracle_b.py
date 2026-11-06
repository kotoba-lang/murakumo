#!/usr/bin/env python3
"""Local oracle for the block batching (not a gate, not a route): ComfyUI v0.44.0's own SDTokenizer.tokenize_with_weights on a prompts file. Per
prompt, per block of 77: a line of the 77 ids and a line "w" + the 77 weights (fixed point, round(weight * 65536)).
  PYTHONPATH=<stubs>:<ComfyUI> python3 oracle_b.py <tokenizer dir> <prompts file>"""
import sys, logging
logging.disable(logging.CRITICAL)
from comfy.sd1_clip import SDTokenizer
t = SDTokenizer(tokenizer_path=sys.argv[1], max_length=77, pad_with_end=True, embedding_directory=None, embedding_size=768, embedding_key="clip_l",
                has_start_token=True, pad_to_max_length=True)
for line in open(sys.argv[2], encoding="utf-8").read().split("\n")[:-1]:
    for b in t.tokenize_with_weights(line):
        print(" ".join(str(x[0]) for x in b))
        print("w " + " ".join(str(round(x[1] * 65536)) for x in b))
