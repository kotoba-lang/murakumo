#!/bin/sh
# Gate the CLIP tokenizer in Kotoba on the Metal route (I3b, first slice): clip_tokenizer_metal.kotoba.in built with
# `bin/amu compile --runtime-profile amu-program-metal` reads ComfyUI's tokenizer files (vocab.json, merges.txt), builds the
# vocabulary and merge-rank hash tables and tokenizes each line of prompts.txt. oracle.py (Python with transformers'
# CLIPTokenizer, the class ComfyUI loads from the same files; a local oracle only) prints what each line must give.
# Gate: every line of output equals the oracle's (ids with BOS/EOS, truncated to 77). Mutants must change at least one
# line: the merge rank order reversed; letters not lowercased; the contraction rule dropped. The timing line is the wall
# time of one whole run (table build included): diagnostic. A Metal-route host is required (not a CI step).
# Non-ASCII text (slice 41): a second corpus, prompts_unicode.txt (make_unicode_prompts.py: "a<c>b" for about 17,700 code points across the
# supported ranges plus every letter + combining mark pair that NFC composes into a supported letter, and mixed lines), must also equal the
# oracle (it NFC-normalizes: a letter plus one combining mark is composed here with a generated table); characters outside the supported set,
# and marks that do not compose, must be refused with a message, not tokenized differently; more mutants cover the Unicode logic.
# The special-token spellings <|startoftext|> / <|endoftext|> in a prompt (case-insensitive, splitting the text) and HTML entities (which this tokenizer
# does not unescape either) are covered by prompts.txt.
# Not covered: ComfyUI's prompt weighting and chunking of prompts longer than 75 tokens (the
# program truncates, as the Hugging Face call does), scripts outside the supported set (Cyrillic, Greek, Arabic, Indic ...).
#
#   sh run.sh /path/to/amu-checkout /path/to/comfy/sd1_tokenizer [python3]
set -eu
AMU=${1:?amu checkout}; TOKDIR=${2:?tokenizer dir (vocab.json, merges.txt)}; PY=${3:-python3.11}; STUBS=${4:-}; COMFY=${5:-}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/clip-tok.XXXXXX")
fail=0
python3 "$HERE/make_unicode_prompts.py" > "$WK/prompts_unicode.txt"
"$PY" "$HERE/oracle.py" "$TOKDIR" "$HERE/prompts.txt" > "$WK/expect.txt" 2>/dev/null
"$PY" "$HERE/oracle.py" "$TOKDIR" "$WK/prompts_unicode.txt" > "$WK/expect_u.txt" 2>/dev/null
PROMPTS=${PROMPTS:-$HERE/prompts.txt}
build() { # name [from to]
  sed -e "s#@VOCAB@#$TOKDIR/vocab.json#;s#@MERGES@#$TOKDIR/merges.txt#;s#@PROMPTS@#$PROMPTS#;s#@WLINES@#${WLINES:-0}#;s#@WEIGHTS@#${WEIGHTS:-0}#" "$HERE/clip_tokenizer_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${2:-}" ]; then
    FROM=$2 TO=$3 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
}
run() { { "$WK/$1" > /dev/null 2> "$WK/$1.err"; } 2>/dev/null || true; tail -n +2 "$WK/$1.err" > "$WK/$1.out"; }
build tk
t0=$(perl -MTime::HiRes=time -e 'print time'); run tk; t1=$(perl -MTime::HiRes=time -e 'print time')
n=$(wc -l < "$WK/expect.txt" | tr -d ' ')
if cmp -s "$WK/tk.out" "$WK/expect.txt"; then echo "all $n prompt lines equal the Hugging Face CLIPTokenizer ($(head -1 "$WK/tk.err"))"
else echo "MISMATCH"; diff "$WK/expect.txt" "$WK/tk.out" | head -6; fail=1; fi
mut() { # label from to [corpus: a|u]
  if [ "${4:-a}" = u ]; then ex="$WK/expect_u.txt"; PROMPTS="$WK/prompts_unicode.txt" build "m$1" "$2" "$3"; else ex="$WK/expect.txt"; build "m$1" "$2" "$3"; fi
  run "m$1"
  if cmp -s "$WK/m$1.out" "$ex"; then echo "mutant [$1] NOT detected"; fail=1
  else echo "mutant [$1]: $(diff "$ex" "$WK/m$1.out" | grep -c '^<') of $(wc -l < "$ex" | tr -d ' ') lines differ (detected)"; fi
}
# prompt weighting (slice 44; needs ComfyUI v0.44.0 and its stubs as arguments 4 and 5): ComfyUI's own SDTokenizer is the oracle for the ids and the
# weights of every token of prompts_weights.txt; weights are fixed point (65536 = 1.0) and must agree within 8 units (the products of 1.1 are rounded
# at each level here, in doubles there).
wcmp() { python3 - "$WK/expect_w.txt" "$WK/$1.out" <<'PY'
import sys
a = open(sys.argv[1]).read().split("\n")[:-1]; b = open(sys.argv[2]).read().split("\n")[:-1]
bad = 0
if len(a) != len(b): print("line count", len(a), len(b)); sys.exit(1)
for i, (x, y) in enumerate(zip(a, b)):
    try:
        if x.startswith("w "):
            xs = list(map(int, x.split()[1:])); ys = list(map(int, y.split()[1:]))
            ok = len(xs) == len(ys) and all(abs(u - v) <= 8 for u, v in zip(xs, ys))
        else: ok = x == y
    except Exception: ok = False
    if not ok:
        bad += 1
        if len(sys.argv) < 3: print("line", i, "expected", x[:100], "got", y[:100])
sys.exit(1 if bad else 0)
PY
}
if [ -n "${COMFY:-}" ]; then
  PYTHONPATH="$STUBS:$COMFY" "$PY" "$HERE/oracle_w.py" "$TOKDIR" "$HERE/prompts_weights.txt" > "$WK/expect_w.txt" 2>/dev/null
  PROMPTS="$HERE/prompts_weights.txt" WLINES=1 WEIGHTS=1 build tkw; run tkw
  if wcmp tkw; then echo "all $(($(wc -l < "$WK/expect_w.txt") / 2)) weighted prompts equal ComfyUI's SDTokenizer (ids exact, weights within 8/65536)"; else echo "WEIGHT MISMATCH"; fail=1; fi
  wmut() { # label from to: the weighted gate must fail
    PROMPTS="$HERE/prompts_weights.txt" WLINES=1 WEIGHTS=1 build "w$1" "$2" "$3"; run "w$1"
    if wcmp "w$1" quiet; then echo "mutant [$1] NOT detected"; fail=1; else echo "mutant [$1]: weighted gate fails (detected)"; fi
  }
  wmut paren-factor '(quot (+ (* w 11) (if (>= w 0) 5 -5)) 10)' '(quot (+ (* w 12) (if (>= w 0) 5 -5)) 10)'
  wmut escape 'esc (if (and (= 1 1) (and (= b0 92)' 'esc (if (and (= 7 1) (and (= b0 92)'
  wmut explicit-weight 'w2 (if (= fp (wsent)) (fp-mul11 tw) fp)' 'w2 (if (= fp (wsent)) (fp-mul11 tw) (fp-mul11 fp))'
  wmut boundary 'a (vector-assoc! CP k 32) b (vector-assoc! CL k 0) x (vector-assoc! WT k 0)]
    (+ k 1)))' 'a (vector-assoc! CP k 32) b (vector-assoc! CL k 0) x (vector-assoc! WT k 0)]
    k))'
fi
# a variable assignment before a shell function call persists in this shell: put the defaults back
WLINES=0; WEIGHTS=0; PROMPTS=$HERE/prompts.txt
mut rank '(if (and (>= r 0) (< r br))' '(if (and (>= r 0) (> r br))'
mut lowercase '(< c 128) (if (within c 65 90) (+ c 32) c)' '(< c 128) c'
mut special-token '(if (= (match-lit f p i e "<|startoftext|>") 1) 1' '(if (= (match-lit f p i e "<|startoftext|>") 7) 1'
mut contraction '(or (= n1 115) (or (= n1 116) (or (= n1 109) (= n1 100)))) 2' '(= n1 1000) 2'
# the Unicode logic, against the unicode corpus
PROMPTS="$WK/prompts_unicode.txt" build tku
run tku
if cmp -s "$WK/tku.out" "$WK/expect_u.txt"; then echo "all $(wc -l < "$WK/expect_u.txt" | tr -d ' ') unicode corpus lines equal the Hugging Face CLIPTokenizer ($(head -1 "$WK/tku.err"))"
else echo "UNICODE MISMATCH"; diff "$WK/expect_u.txt" "$WK/tku.out" | head -6; fail=1; fi
mut cjk-class '(within c 19968 40959) 1' '(within c 19968 40959) 3' u
mut utf8-3byte '(* (+ (* (bit-and b0 15) 64) (bit-and b1 63)) 64)' '(* (+ (* (bit-and b0 7) 64) (bit-and b1 63)) 64)' u
mut latin1-lower '(or (within c 192 214) (within c 216 222)) (+ c 32)' '(or (within c 192 214) (within c 216 222)) c' u
mut ideographic-space '(= c 8287) (= c 12288)' '(= c 8287) (= c 1)' u
mut byte-map '(<= b 32) (+ 256 b)' '(<= b 32) (+ 257 b)' u
mut nfc-compose 'k2 (if (= m 1) (- k 1) k)' 'k2 k' u
mut fullwidth-lower '(within c 65313 65338) (+ c 32)' '(within c 65313 65338) c' u
# refusals: each of these must be refused with a message and no token ids
ref() { # label text-as-printf
  printf "$2\n" > "$WK/r_$1.txt"
  PROMPTS="$WK/r_$1.txt" build "r$1"; run "r$1"
  if grep -a -q '^refused' "$WK/r$1.err" && ! grep -a -q '^49406' "$WK/r$1.out"; then echo "refused [$1]: $(grep -a '^refused' "$WK/r$1.err" | cut -c1-60) (detected)"; else echo "refused [$1]: NOT refused"; fail=1; fi
}
ref cyrillic 'cat \320\277\321\200\320\270\320\262\320\265\321\202'
ref combining-no-composition 'b\314\201'
ref two-marks 'a\314\201\314\201'
ref leading-mark '\314\201a'
ref titlecase 'a\307\205b'
ref invalid-utf8 'a\377b'
ref truncated-utf8 'a\343\201'
ref angle-bracket 'a\342\214\251b'
echo "time: $(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.2f", b-a}') s for the whole run incl. table build (diagnostic)"
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
