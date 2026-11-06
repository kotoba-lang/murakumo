#!/bin/sh
# Gate and time an SDXL-style SpatialTransformer block run end to end on this Mac's GPU (stage M, slice 9): one
# Kotoba program built by `bin/amu compile --runtime-profile amu-program-metal` dispatches 21 kernels (GroupNorm
# to tokens, linear projections with bias and residual epilogues, LayerNorm, fused attention for self- and
# cross-attention, GEGLU, token-layout residual) into ONE command buffer and one encoder. spatial_gpu_ref.c
# computes the same block in double precision on the parameters the f32 CPU twin draws, and prints the GPU's error
# and, for scale, the CPU twin's. Error is |gpu - ref| / max(1, |ref|). The limit is 0.1: about twice the 5.6e-2 the
# first run gave, so it records the observed scale (outputs reach ~100, so this is ~5e-4 of the largest value) and
# is not an independent prediction. Mutants must exceed 0.5: cross-attention keys taken from the image tokens, the
# attention-output residual dropped, GEGLU reading the gate as the value, LayerNorm gamma and beta swapped.
# ms is the best of 20 whole-sequence runs, on whatever load the host has: diagnostic only. A GPU host is required
# (not a CI step). Needs cc, perl, awk.
#
#   sh run.sh /path/to/amu-checkout
set -eu
AMU=${1:?amu checkout}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/spatial-metal.XXXXXX")
fail=0
DEFS="-DSC=128 -DSH=8 -DSW=8 -DSG=32 -DSNH=2 -DSLC=16 -DSCC=24 -DSFI=512"
cc -O2 -ffp-contract=off -I"$HERE/.." $DEFS -o "$WK/ref" "$HERE/spatial_gpu_ref.c" -lm
build() { # name [from to]
  sed -e "s/@REPS@/20/g" "$HERE/spatial_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${2:-}" ]; then
    FROM=$2 TO=$3 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
}
errs() { # name -> "gpu_err cpu_err"
  "$WK/$1" > /dev/null 2> "$WK/$1.err" || true
  head -c 32768 "$WK/$1.err" > "$WK/$1.f32"; tail -c +32769 "$WK/$1.err" > "$WK/$1.rep"
  "$WK/ref" < "$WK/$1.f32" | awk '{print $4, $6}'
}
build sp
set -- $(errs sp); ge=$1; ce=$2
ok=$(awk -v e="$ge" 'BEGIN{print (e+0 <= 0.1) ? 1 : 0}'); [ "$ok" = 1 ] || fail=1
ns=$(sed -n 's/.*best \([0-9]*\) total.*/\1/p' "$WK/sp.rep")
echo "SpatialTransformer 128ch 8x8, 2 heads, 16 ctx tokens: gpu_err=$ge (limit 0.1) cpu_f32_err=$ce $([ "$ok" = 1 ] && echo ok || echo FAIL) best=$(awk -v n="$ns" 'BEGIN{printf "%.2f", n/1e6}') ms for 21 dispatches"
mut() { # label from to
  build "m$1" "$2" "$3"; me=$(errs "m$1" | awk '{print $1}')
  bad=$(awk -v e="$me" 'BEGIN{print (e+0 > 0.5) ? 1 : 0}')
  [ "$bad" = 1 ] && echo "mutant [$1]: error $me (detected)" || { echo "mutant [$1]: error $me NOT detected"; fail=1; }
}
mut crosskeys 'h-ctx h-wk2' 'ln-hb h-wk2'
mut residual 'if (RES == 1u) v += Res[m * N + n];' 'if (RES == 9u) v += Res[m * N + n];'
mut gegluGate 'float a = X[r * 2u * NN + c];' 'float a = X[r * 2u * NN + NN + c];'
mut lnAffine '((X[base + i] - mean) * inv) * Ga[i] + Be[i]' '((X[base + i] - mean) * inv) * Be[i] + Ga[i]'
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
