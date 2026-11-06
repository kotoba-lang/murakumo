#!/usr/bin/env python3
"""Generate prompts_unicode.txt: one line "a<c>b" for code points c across the ranges the Kotoba tokenizer supports (every code point of the
small ranges, every 3rd of the large CJK / Hangul / emoji ranges plus both ends), so that each class (whitespace, letter, number, other) and
each lower-casing rule is exercised against the Hugging Face CLIPTokenizer; plus a few mixed lines. The ranges are the ones cls() accepts.
  python3 make_unicode_prompts.py > prompts_unicode.txt"""
import sys
R = [(28, 31), (128, 255), (256, 303), (305, 383), (5760, 5760), (8192, 8303), (8400, 8447), (8592, 9311), (9472, 10101),
     (10132, 11263), (11776, 11903), (12288, 12351), (12352, 12447), (12448, 12543), (12593, 12686), (13312, 19903), (19968, 40959),
     (44032, 55203), (65024, 65039), (65281, 65381), (65382, 65439), (65504, 65518), (127744, 128591), (128640, 128767), (129280, 129535)]
NO = {9001, 9002, 10972, 12441, 12442} | set(range(768, 880))
BIG = 3000
out = []
for a, b in R:
    step = 3 if b - a > BIG else 1
    cps = set(range(a, b + 1, step)) | {a, b, a + 1, b - 1}
    for c in sorted(cps):
        if a <= c <= b and c not in NO:
            out.append("a" + chr(c) + "b")
out += ["Ünïcödé ÀÉÎÕÜ straße ÿŸ", "ŁÓDŹ ĞÜŞ ŉ ĸ", "  　全角スペース と 空白  ", "Ａｂｃ ＤＥＦ １２３ ｱｲｳｴｵ ﾊﾟｰ",
        "(masterpiece:1.2) 美少女, 桜, 夜景、星空。", "a b c\u0085d", "x​y‍z \U0001F468‍\U0001F469", "I’m don’t 'tis ’s",
        "日本語と English の mixed プロンプト 123 ４５６", "ＧＰＴ ｇｐｔ ｇｐｔ’ｓ"]
import unicodedata
# NFC: every (letter, combining mark) pair that composes into a supported letter, with the base in lower and upper case, between ASCII letters
bases = list(range(65, 91)) + list(range(97, 123)) + list(range(0xC0, 0x180)) + list(range(0x3041, 0x3100))
marks = list(range(0x300, 0x370)) + [0x3099, 0x309A]
for b in bases:
    if b in (0xD7, 0xF7, 0x130):
        continue
    for m in marks:
        n = unicodedata.normalize("NFC", chr(b) + chr(m))
        if len(n) == 1 and ((0xC0 <= ord(n) <= 0xFF and ord(n) not in (0xD7, 0xF7)) or (0x100 <= ord(n) <= 0x17F and ord(n) != 0x130) or (0x3041 <= ord(n) <= 0x30FF)):
            out.append("x" + chr(b) + chr(m) + "y")
out += ["\u304b\u3099\u304d\u3099 \u30cf\u309a\u30f3 e\u0301cole A\u030a ngstr\u00f6m", "o\u0308 O\u0308 U\u0308 \u00dc"]
sys.stdout.write("\n".join(out) + "\n")
