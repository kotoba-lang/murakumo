#!/bin/sh
# Gate a ComfyUI UNetModel (mini configuration) run end to end on the GPU (stage M, slice 10): one Kotoba program built by
# `bin/amu compile --runtime-profile amu-program-metal` reads ComfyUI-named weights from a safetensors file and runs the whole graph (time
# embedding, 4 input blocks, the middle block, 4 output blocks with skip concatenation, Downsample and Upsample, the output head) in ONE
# command buffer. make_fixture.py builds the same model with ComfyUI v0.44.0's own PyTorch UNetModel (random weights, float32, CPU; a local
# oracle only) and records its output and the activations after each block; compare.py prints the largest |gpu - ref| / max(1, |ref|) per
# stage. Limits: final output 1e-2 and every stage 2e-2 (the f16 scale of the earlier slices, set before this slice's first run).
# Mutants must push the final error above 0.05: the time embedding left out of the ResBlocks, the Upsample reading the wrong source row,
# the final ResBlock's skip dropped. ms is the best of 20 whole-graph runs, on whatever load the host has: diagnostic. A GPU host is
# required (not a CI step).
#
#   sh run.sh /path/to/amu-checkout /path/to/ComfyUI-source /path/to/stubs /path/to/python-with-torch [python3 for compare]
set -eu
AMU=${1:?amu checkout}; COMFY=${2:?ComfyUI v0.44.0 source dir}; STUBS=${3:?stubs dir (comfy_kitchen, comfy_aimdo)}; PYC=${4:?python with torch, safetensors, einops}; PY=${5:-python3.11}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/unet-metal.XXXXXX")
fail=0
PYTHONPATH="$STUBS" "$PYC" "$HERE/make_fixture.py" write "$WK/fx" "$COMFY" > "$WK/fx.log" 2>&1
TOTAL=258560
build() { # name [from to]
  sed -e "s#@MODEL@#$WK/fx/model.safetensors#;s#@X@#$WK/fx/x.f32#;s#@CTX@#$WK/fx/ctx.f32#;s#@REPS@#20#" "$HERE/unet_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${2:-}" ]; then
    FROM=$2 TO=$3 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
}
run() { # name -> prints compare.py's two lines
  { "$WK/$1" > /dev/null 2> "$WK/$1.err"; } 2>/dev/null || true
  head -c $TOTAL "$WK/$1.err" > "$WK/$1.f32"; tail -c +$((TOTAL + 1)) "$WK/$1.err" > "$WK/$1.rep"
  "$PY" "$HERE/compare.py" "$WK/$1.f32" "$WK/fx"
}
build un
run un > "$WK/un.cmp" 2>/dev/null || true
stages=$(sed -n 1p "$WK/un.cmp"); fin=$(sed -n 2p "$WK/un.cmp" | awk '{print $3}'); worst=$(sed -n 1p "$WK/un.cmp" | tr ' ' '\n' | sed 's/.*=//' | sort -g | tail -1)
ok=$(awk -v e="$fin" -v w="$worst" 'BEGIN{print (e+0 <= 0.01 && w+0 <= 0.02) ? 1 : 0}'); [ "$ok" = 1 ] || fail=1
ns=$(sed -n 's/.*best \([0-9]*\) total.*/\1/p' "$WK/un.rep")
echo "mini UNetModel on the GPU: $stages"
echo "final=$fin worst stage=$worst (limits 0.01 / 0.02) $([ "$ok" = 1 ] && echo ok || echo FAIL) best=$(awk -v n="$ns" 'BEGIN{printf "%.2f", n/1e6}') ms for the whole graph"
mut() { # label from to
  build "m$1" "$2" "$3"; run "m$1" > "$WK/m$1.cmp" 2>/dev/null || true
  x=$(sed -n 2p "$WK/m$1.cmp" | awk '{print $3}')
  if awk -v e="${x:-1e9}" 'BEGIN{exit !(e+0 > 0.05)}'; then echo "mutant [$1]: final error $x (detected)"; else echo "mutant [$1]: final error ${x:-none} NOT detected"; fail=1; fi
}
mut timeemb 'float acc = Bt[c] + C1[c];' 'float acc = C1[c];'
mut upsample 'O[i] = half(X[(c * IH + y / 2u) * IW + x / 2u]);' 'O[i] = half(X[(c * IH + y / 3u) * IW + x / 2u]);'
mut residual 'if (MODE == 2u) v += Res[m * NP + p];' 'if (MODE == 9u) v += Res[m * NP + p];'
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
