#!/bin/sh
# Gate and time the f16 matrix-unit GEMM on data that are not small integers (stage M, slice 7).
#   metal   gemm_f16_metal.kotoba.in built with `bin/amu compile --runtime-profile amu-program-metal`
#   ref     gemm_ref.c, host cc: the same inputs in double, unrounded (exact) and rounded to half
# Reports two normalised errors, |gpu - ref| / max(1, |ref|): against the half-rounded values (float32
# accumulation only; limit 4 * K * 2^-24) and against the unrounded values (f16 storage rounding too; limit
# 2 * 2^-11 * sqrt(K), the scale of a random walk of K products each carrying a 2^-11 relative error).
# Both limits come from that arithmetic, not from tuning to the measurements. A mutant (B read with the
# wrong row stride) must exceed 0.1. GMAC/s over the best of 20
# dispatches, command-buffer floor included, on whatever load the host has: diagnostic only. A GPU host is
# required (not a CI step). Needs a cc with _Float16 (Apple clang), awk, bc.
#
#   sh run.sh /path/to/amu-checkout
set -eu
AMU=${1:?amu checkout}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/gemm-f16.XXXXXX")
CLI="$AMU/bin/amu"
fail=0
build() { # name M K N [from to]
  sed -e "s/@M@/$2/g;s/@K@/$3/g;s/@N@/$4/g;s/@REPS@/20/g;s/@VMASK@/32767/g;s/@VOFF@/16384/g" "$HERE/gemm_f16_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${5:-}" ]; then
    FROM=$5 TO=$6 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$CLI" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
}
errs() { # name M K N -> "err_exact err_half"
  bytes=$(( $2 * $4 * 4 ))
  "$WK/$1" > /dev/null 2> "$WK/$1.err" || true
  head -c "$bytes" "$WK/$1.err" > "$WK/$1.f32"; tail -c +$((bytes + 1)) "$WK/$1.err" > "$WK/$1.rep"
  cc -O2 -DM="$2" -DK="$3" -DN="$4" -o "$WK/ref_$1" "$HERE/gemm_ref.c" -lm
  "$WK/ref_$1" < "$WK/$1.f32" | awk '{print $4, $6}'
}
shape() { # M K N
  id="$1_$2_$3"; build "g$id" "$1" "$2" "$3"; set -- "$@" $(errs "g$id" "$1" "$2" "$3"); ee=$4; eh=$5
  lh=$(awk -v k="$2" 'BEGIN{printf "%.2e", 4*k/16777216}'); lu=$(awk -v k="$2" 'BEGIN{printf "%.2e", 2*sqrt(k)/2048}')
  ok=$(awk -v a="$ee" -v b="$eh" -v lu="$lu" -v lh="$lh" 'BEGIN{print (a+0 <= lu+0 && b+0 <= lh+0) ? 1 : 0}'); [ "$ok" = 1 ] || fail=1
  ns=$(sed -n 's/.*best \([0-9]*\) total.*/\1/p' "$WK/g$id.rep")
  echo "M=$1 K=$2 N=$3 err_vs_half=$eh (limit $lh) err_vs_unrounded=$ee (limit $lu) $([ "$ok" = 1 ] && echo ok || echo FAIL) best_ns=$ns GMAC/s=$(echo "scale=1; $1*$2*$3 / $ns" | bc)"
}
shape 512 512 640
shape 2304 640 640
build mut 512 512 640 'col0 + j * 8u, NN)' 'col0 + j * 8u, KK)'
merr=$(errs mut 512 512 640 | awk '{print $2}')
bad=$(awk -v e="$merr" 'BEGIN{print (e+0 > 0.1) ? 1 : 0}')
[ "$bad" = 1 ] && echo "mutant (wrong B stride): error $merr (detected)" || { echo "mutant NOT detected ($merr)"; fail=1; }
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
