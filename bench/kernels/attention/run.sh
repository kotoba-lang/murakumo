#!/bin/sh
# Gate and time non-causal multi-head attention (f32) built two ways on one Mac:
#   amu-macos  attention.kotoba.in compiled by the Amu selfhost Mac CLI
#   c-O2       attention.c, host cc, -ffp-contract=off
# Gate: both print the same checksum at REPS=1 and REPS=N (bit-exact f32), a
# mutated kernel (the softmax row maximum taken over the first key only) prints
# a different one, and
# the f32 twin stays within 1e-4 of an f64 reference (libm exp).
# Timing is (t[REPS] - t[1]) / (REPS-1). Local measurement only; not a CI step.
#
#   sh run.sh /path/to/amu-checkout [NH D LQ LK REPS]
set -eu
AMU=${1:?amu checkout}; NH=${2:-4}; D=${3:-16}; LQ=${4:-64}; LK=${5:-64}; REPS=${6:-10}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=${TMPDIR:-/tmp}/attention-$$; mkdir -p "$WK"
CLI="$AMU/bin/amu"
subst() { sed -e "s/@NH@/$NH/g;s/@D@/$D/g;s/@LQ@/$LQ/g;s/@LK@/$LK/g;s/@REPS@/$1/g" "$HERE/attention.kotoba.in"; }
DEFS="-DNH=$NH -DD=$D -DLQ=$LQ -DLK=$LK"
ms() { s=$(perl -MTime::HiRes=time -e 'print time'); "$@" >/dev/null; e=$(perl -MTime::HiRes=time -e 'print time'); echo "($e-$s)*1000" | bc -l; }
for r in 1 "$REPS"; do
  subst "$r" > "$WK/k$r.kotoba"; "$CLI" compile "$WK/k$r.kotoba" -o "$WK/amu$r" >/dev/null
  cc -O2 -ffp-contract=off $DEFS -DREPS="$r" -o "$WK/c$r" "$HERE/attention.c" -lm
done
# mutant: stop the max scan at the first key, so the softmax subtracts the wrong constant.
subst 1 | sed 's/(if (or (= j 0) (f32-gt (fb s) (fb m))) s m)/(if (= j 0) s m)/' > "$WK/mut.kotoba"
cmp -s "$WK/mut.kotoba" "$WK/k1.kotoba" && { echo "mutation did not apply"; exit 1; }
"$CLI" compile "$WK/mut.kotoba" -o "$WK/mut" >/dev/null
cc -O2 -ffp-contract=off $DEFS -o "$WK/oracle" "$HERE/attention_oracle.c" -lm
fail=0
for r in 1 "$REPS"; do
  a=$("$WK/amu$r"); c=$("$WK/c$r")
  [ "$a" = "$c" ] && echo "reps=$r checksum equal: $a" || { echo "reps=$r MISMATCH amu=$a c=$c"; fail=1; }
done
m=$("$WK/mut"); c1=$("$WK/c1")
[ "$m" != "$c1" ] && echo "mutant differs: $m != $c1" || { echo "mutant NOT detected"; fail=1; }
echo "oracle: $("$WK/oracle")"
tol=$("$WK/oracle" | awk '{print ($4<1e-4)?"ok":"FAIL"}'); echo "abs err <= 1e-4: $tol"; [ "$tol" = ok ] || fail=1
[ "$fail" = 0 ] || { echo "GATE FAILED"; exit 1; }
for n in 1 2 3; do
  for b in amu c; do
    t1=$(ms "$WK/${b}1"); tn=$(ms "$WK/${b}$REPS")
    echo "$b round $n: $(echo "($tn-$t1)/($REPS-1)" | bc -l | cut -c1-8) ms per pass (diagnostic)"
  done
done
echo "gate passed (NH=$NH D=$D LQ=$LQ LK=$LK REPS=$REPS), build dir $WK"
