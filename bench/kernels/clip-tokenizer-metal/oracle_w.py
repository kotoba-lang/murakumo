#!/usr/bin/env python3
"""Local oracle for the weighted tokenizer (not a gate, not a route): ComfyUI v0.44.0's own SDTokenizer.tokenize_with_weights (escape_important,
parse_parentheses, token_weights and the Hugging Face CLIPTokenizer on each segment) on a prompts file. Per line prints the ids as the Kotoba
program does (BOS, the tokens up to the first end token, EOS) and a second line "w <fixed point weights>" (round(weight * 65536)) of those tokens.
  PYTHONPATH=<stubs>:<ComfyUI> python3 oracle_w.py <tokenizer dir> <prompts file>"""
import sys, logging
logging.disable(logging.CRITICAL)
from comfy.sd1_clip import SDTokenizer
t = SDTokenizer(tokenizer_path=sys.argv[1], max_length=77, pad_with_end=True, embedding_directory=None, embedding_size=768, embedding_key="clip_l",
                has_start_token=True, pad_to_max_length=True)
for line in open(sys.argv[2], encoding="utf-8").read().split("\n")[:-1]:
    b = t.tokenize_with_weights(line)[0]
    e = next(i for i in range(1, len(b)) if b[i][0] == 49407 and b[i][1] == 1.0 and all(x[0] == 49407 for x in b[i:]))
    print(" ".join(str(x[0]) for x in b[:e + 1]))
    print("w " + " ".join(str(round(x[1] * 65536)) for x in b[1:e]))
