#!/usr/bin/env python3
"""Local oracle for clip_tokenizer_metal.kotoba.in (not a gate, not a route): Hugging Face's CLIPTokenizer, the class
ComfyUI's SDTokenizer loads from comfy/sd1_tokenizer, on a prompts file. Prints, per line, the ids with
<|startoftext|> and <|endoftext|>, truncated to 77 (BOS + at most 75 tokens + EOS), as the Kotoba program prints them.
  python3 oracle.py <tokenizer dir> <prompts file>"""
import sys
from transformers import CLIPTokenizer
tok = CLIPTokenizer.from_pretrained(sys.argv[1])
for line in open(sys.argv[2], encoding="utf-8").read().split("\n")[:-1]:
    print(" ".join(map(str, tok(line, truncation=True, max_length=77)["input_ids"])))
