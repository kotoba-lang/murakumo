#!/bin/sh
# Gate and time the f16 matrix-unit GEMM (stage M, slice 1) on this Mac's GPU.
#   metal   gemm_metal.kotoba.in built with `bin/amu compile --runtime-profile amu-program-metal`
#   c       gemm.c, host cc (an exact integer twin)
# Data are small integers (-64..63) stored as half; all products and partial sums are
# integers float32 holds exactly, so the checksum must equal the twin's whatever order the
# matrix units add in. A mutant (B read with the wrong row stride) must change it.
# GMAC/s is the best of the timed dispatches, command-buffer floor included, on whatever
# load the host has: diagnostic only. A GPU host is required (not a CI step). Needs cc, bc.
#
#   sh run.sh /path/to/amu-checkout ["M K N" ...]   (default: the gate shape plus three timing shapes)
set -eu
AMU=${1:?amu checkout}; shift || true
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/gemm-metal.XXXXXX")
CLI="$AMU/bin/amu"
[ "$#" -gt 0 ] || set -- "512 512 640" "2304 640 640" "2304 640 2560" "4096 1024 4096"
build() { # name M K N reps [sed-from sed-to]
  n=$1; sed -e "s/@M@/$2/g;s/@K@/$3/g;s/@N@/$4/g;s/@REPS@/$5/g" "$HERE/gemm_metal.kotoba.in" > "$WK/$n.kotoba"
  if [ -n "${6:-}" ]; then
    FROM=$6 TO=$7 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$n.kotoba"
  fi
  "$CLI" compile "$WK/$n.kotoba" --runtime-profile amu-program-metal -o "$WK/$n" >/dev/null
}
fail=0; first=1
for s in "$@"; do
  set -- $s; M=$1; K=$2; N=$3; id="${M}_${K}_${N}"
  build "g$id" "$M" "$K" "$N" 20
  cc -O2 -DM="$M" -DK="$K" -DN="$N" -o "$WK/c$id" "$HERE/gemm.c"
  c=$("$WK/c$id"); m=$("$WK/g$id" 2> "$WK/err$id"); ns=$(sed -n 's/.*best \([0-9]*\) total.*/\1/p' "$WK/err$id")
  eq=no; [ "$c" = "$m" ] && eq=yes || fail=1
  echo "M=$M K=$K N=$N twin=$c metal=$m equal=$eq best_ns=$ns GMAC/s=$(echo "scale=1; $M*$K*$N / $ns" | bc)"
  if [ "$first" = 1 ]; then
    first=0
    build "mut$id" "$M" "$K" "$N" 1 'col0 + j * 8u, NN)' 'col0 + j * 8u, KK)'
    mm=$("$WK/mut$id" 2>/dev/null || echo TRAP)
    [ "$mm" != "$c" ] && echo "mutant (wrong B stride) differs: $mm != $c" || { echo "mutant NOT detected"; fail=1; }
  fi
done
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
