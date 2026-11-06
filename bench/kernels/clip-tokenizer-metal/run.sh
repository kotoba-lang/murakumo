#!/bin/sh
# Gate the CLIP tokenizer in Kotoba on the Metal route (I3b, first slice): clip_tokenizer_metal.kotoba.in built with
# `bin/amu compile --runtime-profile amu-program-metal` reads ComfyUI's tokenizer files (vocab.json, merges.txt), builds the
# vocabulary and merge-rank hash tables and tokenizes each line of prompts.txt. oracle.py (Python with transformers'
# CLIPTokenizer, the class ComfyUI loads from the same files; a local oracle only) prints what each line must give.
# Gate: every line of output equals the oracle's (ids with BOS/EOS, truncated to 77). Mutants must change at least one
# line: the merge rank order reversed; letters not lowercased; the contraction rule dropped. The timing line is the wall
# time of one whole run (table build included): diagnostic. A Metal-route host is required (not a CI step).
# Not covered: non-ASCII text, HTML entities, the special-token spellings, ComfyUI's prompt weighting and
# chunking of prompts longer than 75 tokens (the program truncates, as the Hugging Face call does).
#
#   sh run.sh /path/to/amu-checkout /path/to/comfy/sd1_tokenizer [python3]
set -eu
AMU=${1:?amu checkout}; TOKDIR=${2:?tokenizer dir (vocab.json, merges.txt)}; PY=${3:-python3.11}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/clip-tok.XXXXXX")
fail=0
"$PY" "$HERE/oracle.py" "$TOKDIR" "$HERE/prompts.txt" > "$WK/expect.txt" 2>/dev/null
build() { # name [from to]
  sed -e "s#@VOCAB@#$TOKDIR/vocab.json#;s#@MERGES@#$TOKDIR/merges.txt#;s#@PROMPTS@#$HERE/prompts.txt#" "$HERE/clip_tokenizer_metal.kotoba.in" > "$WK/$1.kotoba"
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
mut() { # label from to
  build "m$1" "$2" "$3"; run "m$1"
  if cmp -s "$WK/m$1.out" "$WK/expect.txt"; then echo "mutant [$1] NOT detected"; fail=1
  else echo "mutant [$1]: $(diff "$WK/expect.txt" "$WK/m$1.out" | grep -c '^<') of $n lines differ (detected)"; fi
}
mut rank '(if (and (>= r 0) (< r br))' '(if (and (>= r 0) (> r br))'
mut lowercase '(defn- lc [b :i64] :i64 (if (and (>= b 65) (<= b 90)) (+ b 32) b))' '(defn- lc [b :i64] :i64 b)'
mut contraction '(or (= n1 115) (or (= n1 116) (or (= n1 109) (= n1 100)))) 2' '(= n1 1000) 2'
echo "time: $(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.2f", b-a}') s for the whole run incl. table build (diagnostic)"
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
