#!/bin/sh
# Gate the CLIP text tower on the GPU, reading Hugging Face safetensors weights (I3b, second slice): one Kotoba program built by
# `bin/amu compile --runtime-profile amu-program-metal` indexes and admits the file's tensors, reads them into a Metal buffer,
# converts the weight matrices to half on the GPU and encodes the whole tower (embedding gather; per layer LayerNorm, q/k/v,
# causal attention, out_proj + residual, LayerNorm, fc1 + quick_gelu, fc2 + residual; final LayerNorm) into one command buffer.
# make_fixture.py (Python 3 with torch, transformers, safetensors, numpy; a local oracle only) writes a small CLIPTextModel with random
# weights as model.safetensors, the 77 ids of a prompt, and the model's float32 last_hidden_state; compare.py prints the largest
# |gpu - ref| / max(1, |ref|). The limit is 1e-2, the f16 scale the GEMM, ResBlock and SpatialTransformer slices measured, set before
# this slice's first run. Mutants must exceed 5e-2: the causal mask off, quick_gelu off, the weights read untransposed, the first
# residual dropped. ms is the best of 20 whole-sequence runs, on whatever load the host has: diagnostic only. A GPU host is required
# (not a CI step).
#
#   sh run.sh /path/to/amu-checkout /path/to/comfy/sd1_tokenizer [python3]
set -eu
AMU=${1:?amu checkout}; TOKDIR=${2:?tokenizer dir (vocab.json, merges.txt)}; PY=${3:-python3.11}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/clip-tower.XXXXXX")
fail=0
"$PY" "$HERE/make_fixture.py" write "$WK/fx" "$TOKDIR" > "$WK/fx.log" 2>&1
EOS=$(sed -n 's/^eos position \([0-9]*\).*/\1/p' "$WK/fx.log")
build() { # name [from to]
  sed -e "s#@MODEL@#$WK/fx/model.safetensors#;s#@IDS@#$WK/fx/ids.txt#;s#@REPS@#20#" "$HERE/clip_tower_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${2:-}" ]; then
    FROM=$2 TO=$3 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
}
errs() { # name -> "max_err pooled_err"
  { "$WK/$1" > /dev/null 2> "$WK/$1.err"; } 2>/dev/null || true
  head -c 39424 "$WK/$1.err" > "$WK/$1.f32"; tail -c +39425 "$WK/$1.err" > "$WK/$1.rep"
  "$PY" "$HERE/compare.py" "$WK/$1.f32" "$WK/fx/expect.f32" "$WK/fx/expect_pooled.f32" "$EOS" | awk '{print $2, $6}'
}
build tw
set -- $(errs tw); me=$1; pe=$2
ok=$(awk -v e="$me" -v p="$pe" 'BEGIN{print (e+0 <= 0.01 && p+0 <= 0.01) ? 1 : 0}'); [ "$ok" = 1 ] || fail=1
ns=$(sed -n 's/.*best \([0-9]*\) total.*/\1/p' "$WK/tw.rep")
echo "CLIP tower 2 layers, hidden 128, 2 heads, 77 tokens: max_err=$me pooled_err=$pe (limit 0.01) $([ "$ok" = 1 ] && echo ok || echo FAIL) best=$(awk -v n="$ns" 'BEGIN{printf "%.2f", n/1e6}') ms"
mut() { # label from to
  build "m$1" "$2" "$3"; x=$(errs "m$1" | awk '{print $1}')
  bad=$(awk -v e="$x" 'BEGIN{print (e+0 > 0.05) ? 1 : 0}')
  [ "$bad" = 1 ] && echo "mutant [$1]: error $x (detected)" || { echo "mutant [$1]: error $x NOT detected"; fail=1; }
}
mut causal 'Pat (pbuf-u f R dev 77 2 1 0' 'Pat (pbuf-u f R dev 77 2 0 0'
mut activation 'Pl1 (pbuf-u f R dev 128 128 512 1 0 1 0' 'Pl1 (pbuf-u f R dev 128 128 512 1 0 0 0'
mut transpose 'simdgroup_load(b[j], W + (n0 + col0 + j * 8u) * K + k, K, ulong2(0, 0), true);' 'simdgroup_load(b[j], W + (n0 + col0 + j * 8u) * K + k, K, ulong2(0, 0), false);'
mut residual 'Plo (pbuf-u f R dev 128 128 128 0 1 0' 'Plo (pbuf-u f R dev 128 128 128 0 0 0'
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
