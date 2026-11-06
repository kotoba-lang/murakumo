#!/bin/sh
# Gate "prompt text in, hidden states out" on the GPU (I3b, third slice): one Kotoba program built by
# `bin/amu compile --runtime-profile amu-program-metal` tokenizes a prompt with the CLIP tokenizer (ComfyUI's vocab.json and merges.txt), pads the ids with
# the end token to 77, and runs the CLIP text tower of clip-tower-metal on Hugging Face safetensors weights, all in one process.
# make_fixture.py (Python 3 with torch, transformers, safetensors, numpy; a local oracle only) writes a small random CLIPTextModel,
# a prompt, the ids Hugging Face's CLIPTokenizer gives it and the model's float32 last_hidden_state. Two gates: (1) the ids the Kotoba
# program prints (after the hidden states) equal the oracle's, (2) the hidden states are within 1e-2 of the oracle's (|gpu - ref| /
# max(1, |ref|), the f16 scale of the earlier slices, set before the first run). Mutants must change one of them: the merge rank order reversed
# (ids), the causal mask off (hidden states). ms is the best of 20 whole-sequence runs of the GPU part (the tokenizer runs once): diagnostic.
# A GPU host is required (not a CI step).
#
#   sh run.sh /path/to/amu-checkout /path/to/comfy/sd1_tokenizer [python3]
set -eu
AMU=${1:?amu checkout}; TOKDIR=${2:?tokenizer dir (vocab.json, merges.txt)}; PY=${3:-python3.11}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/text-encode.XXXXXX")
fail=0
"$PY" "$HERE/make_fixture.py" write "$WK/fx" "$TOKDIR" > "$WK/fx.log" 2>&1
EOS=$(sed -n 's/^eos position \([0-9]*\).*/\1/p' "$WK/fx.log")
build() { # name [from to]
  sed -e "s#@MODEL@#$WK/fx/model.safetensors#;s#@VOCAB@#$TOKDIR/vocab.json#;s#@MERGES@#$TOKDIR/merges.txt#;s#@PROMPT@#$WK/fx/prompt.txt#;s#@REPS@#20#" "$HERE/text_encode_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${2:-}" ]; then
    FROM=$2 TO=$3 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
}
run() { # name -> files name.f32, name.ids, name.rep ; prints "max_err pooled_err"
  { "$WK/$1" > /dev/null 2> "$WK/$1.err"; } 2>/dev/null || true
  head -c 39424 "$WK/$1.err" > "$WK/$1.f32"; tail -c +39425 "$WK/$1.err" > "$WK/$1.tail"
  grep -v '^metal-dispatch' "$WK/$1.tail" | grep -v '^$' > "$WK/$1.ids" || true
  grep '^metal-dispatch' "$WK/$1.tail" > "$WK/$1.rep" || true
  "$PY" "$HERE/compare.py" "$WK/$1.f32" "$WK/fx/expect.f32" "$WK/fx/expect_pooled.f32" "$EOS" | awk '{print $2, $6}'
}
build te
set -- $(run te); me=$1; pe=$2
tr -s ' ' < "$WK/fx/ids.txt" | sed 's/ *$//' > "$WK/want.ids"; sed 's/ *$//' "$WK/te.ids" > "$WK/got.ids"
if cmp -s "$WK/want.ids" "$WK/got.ids"; then idok=1; echo "ids equal the Hugging Face CLIPTokenizer's ($(wc -w < "$WK/want.ids" | tr -d ' ') ids, BOS ... EOS padded)"; else idok=0; fail=1; echo "ids DIFFER"; diff "$WK/want.ids" "$WK/got.ids" | head -3; fi
ok=$(awk -v e="$me" -v p="$pe" 'BEGIN{print (e+0 <= 0.01 && p+0 <= 0.01) ? 1 : 0}'); [ "$ok" = 1 ] || fail=1
ns=$(sed -n 's/.*best \([0-9]*\) total.*/\1/p' "$WK/te.rep")
echo "hidden states: max_err=$me pooled_err=$pe (limit 0.01) $([ "$ok" = 1 ] && echo ok || echo FAIL) best=$(awk -v n="$ns" 'BEGIN{printf "%.2f", n/1e6}') ms"
mut() { # label from to what
  build "m$1" "$2" "$3"; set -- "$1" $(run "m$1")
  sed "s/ *$//" "$WK/m$1.ids" > "$WK/m$1.ids2"
  if ! cmp -s "$WK/want.ids" "$WK/m$1.ids2"; then echo "mutant [$1]: the ids differ (detected)"
  elif awk -v e="$2" 'BEGIN{exit !(e+0 > 0.05)}'; then echo "mutant [$1]: error $2 (detected)"
  else echo "mutant [$1] NOT detected"; fail=1; fi
}
mut rank '(if (and (>= r 0) (< r br))' '(if (and (>= r 0) (> r br))'
mut causal 'Pat (pbuf-u f R dev 77 2 1 0' 'Pat (pbuf-u f R dev 77 2 0 0'
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
