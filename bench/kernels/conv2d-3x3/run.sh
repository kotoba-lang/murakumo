#!/bin/sh
# Gate and time conv2d 3x3 (stride 1, pad 1, bias, f32) built two ways on one Mac:
#   amu-macos  conv2d_3x3.kotoba.in compiled by the Amu selfhost Mac CLI
#   c-O2       conv2d_3x3.c, host cc, -ffp-contract=off
# Gate: both print the same checksum at REPS=1 and REPS=N (bit-exact f32), a
# mutated kernel (kernel window mirrored in x) prints a different one, and the
# f32 twin stays within 1e-3 of an f64-accumulated reference.
# Timing is (t[REPS] - t[1]) / (REPS-1). Local measurement only; not a CI step.
#
#   sh run.sh /path/to/amu-checkout [CI CO H W REPS]
set -eu
AMU=${1:?amu checkout}; CI=${2:-32}; CO=${3:-32}; H=${4:-32}; W=${5:-32}; REPS=${6:-10}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=${TMPDIR:-/tmp}/conv2d-$$; mkdir -p "$WK"
CLI="$AMU/bin/amu"
subst() { sed -e "s/@CI@/$CI/;s/@CO@/$CO/;s/@H@/$H/;s/@W@/$W/;s/@REPS@/$1/" "$HERE/conv2d_3x3.kotoba.in"; }
DEFS="-DCI=$CI -DCO=$CO -DH=$H -DW=$W"
ms() { s=$(perl -MTime::HiRes=time -e 'print time'); "$@" >/dev/null; e=$(perl -MTime::HiRes=time -e 'print time'); echo "($e-$s)*1000" | bc -l; }
for r in 1 "$REPS"; do
  subst "$r" > "$WK/k$r.kotoba"; "$CLI" compile "$WK/k$r.kotoba" -o "$WK/amu$r" >/dev/null
  cc -O2 -ffp-contract=off $DEFS -DREPS="$r" -o "$WK/c$r" "$HERE/conv2d_3x3.c"
done
subst 1 | sed 's/kx (- k (\* ky 3))/kx (- 2 (- k (* ky 3)))/' > "$WK/mut.kotoba"
cmp -s "$WK/mut.kotoba" "$WK/k1.kotoba" && { echo "mutation did not apply"; exit 1; }
"$CLI" compile "$WK/mut.kotoba" -o "$WK/mut" >/dev/null
cc -O2 -ffp-contract=off $DEFS -o "$WK/oracle" "$HERE/conv2d_3x3_oracle.c"
fail=0
for r in 1 "$REPS"; do
  a=$("$WK/amu$r"); c=$("$WK/c$r")
  [ "$a" = "$c" ] && echo "reps=$r checksum equal: $a" || { echo "reps=$r MISMATCH amu=$a c=$c"; fail=1; }
done
m=$("$WK/mut"); c1=$("$WK/c1")
[ "$m" != "$c1" ] && echo "mutant differs: $m != $c1" || { echo "mutant NOT detected"; fail=1; }
echo "oracle: $("$WK/oracle")"
tol=$("$WK/oracle" | awk '{print ($4<1e-3)?"ok":"FAIL"}'); echo "abs err <= 1e-3: $tol"; [ "$tol" = ok ] || fail=1
[ "$fail" = 0 ] || { echo "GATE FAILED"; exit 1; }
for n in 1 2 3; do
  for b in amu c; do
    t1=$(ms "$WK/${b}1"); tn=$(ms "$WK/${b}$REPS")
    echo "$b round $n: $(echo "($tn-$t1)/($REPS-1)" | bc -l | cut -c1-8) ms per pass (diagnostic)"
  done
done
echo "gate passed (CI=$CI CO=$CO H=$H W=$W REPS=$REPS), build dir $WK"
