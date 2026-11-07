#!/bin/sh
# Gate and time GroupNorm+SiLU built two ways on one Mac:
#   amu-macos  groupnorm_silu.kotoba.in compiled by the Amu selfhost Mac CLI
#   c-O2       groupnorm_silu.c, host cc, -ffp-contract=off
# Gate: both print the same checksum at REPS=1 and REPS=N (bit-exact f32), a
# mutated kernel (one polynomial constant moved by 1 ulp) prints a different
# one, and the f32 twin stays within 1e-4 of an f64 reference (libm exp).
# Timing is (t[REPS] - t[1]) / (REPS-1), so start-up and fill cancel.
#
#   sh run.sh /path/to/amu-checkout [C HW G REPS]
# Needs: cc, bin/amu. No Python, no JVM, no nbb. Local measurement only; not a CI step.
set -eu
AMU=${1:?amu checkout}; C=${2:-320}; HW=${3:-1024}; G=${4:-32}; REPS=${5:-20}
HERE=$(cd "$(dirname "$0")" && pwd)
W=${TMPDIR:-/tmp}/gn-silu-$$; mkdir -p "$W"
CLI="$AMU/bin/amu"
subst() { sed -e "s/@C@/$C/;s/@HW@/$HW/;s/@G@/$G/;s/@REPS@/$1/" "$HERE/groupnorm_silu.kotoba.in"; }
ms() { # run argv, print wall ms
  s=$(perl -MTime::HiRes=time -e 'print time'); "$@" >/dev/null; e=$(perl -MTime::HiRes=time -e 'print time'); echo "($e-$s)*1000" | bc -l; }
for r in 1 "$REPS"; do
  subst "$r" > "$W/k$r.kotoba"; "$CLI" compile "$W/k$r.kotoba" -o "$W/amu$r" >/dev/null
  cc -O2 -ffp-contract=off -DC="$C" -DHW="$HW" -DG="$G" -DREPS="$r" -o "$W/c$r" "$HERE/groupnorm_silu.c" -lm
done
subst 1 | sed 's/(fb 1056964608))$/(fb 1056964609))/;s/y4 (f32-add (f32-mul y3 r) (fb 1056964608))/y4 (f32-add (f32-mul y3 r) (fb 1056964609))/' > "$W/mut.kotoba"
"$CLI" compile "$W/mut.kotoba" -o "$W/mut" >/dev/null
cc -O2 -ffp-contract=off -DC="$C" -DHW="$HW" -DG="$G" -o "$W/oracle" "$HERE/groupnorm_silu_oracle.c" -lm
fail=0
for r in 1 "$REPS"; do
  a=$("$W/amu$r"); c=$("$W/c$r")
  [ "$a" = "$c" ] && echo "reps=$r checksum equal: $a" || { echo "reps=$r MISMATCH amu=$a c=$c"; fail=1; }
done
m=$("$W/mut"); c1=$("$W/c1")
[ "$m" != "$c1" ] && echo "mutant differs: $m != $c1" || { echo "mutant NOT detected"; fail=1; }
echo "oracle: $("$W/oracle")"
tol=$("$W/oracle" | awk '{print ($4<1e-4)?"ok":"FAIL"}'); echo "abs err <= 1e-4: $tol"; [ "$tol" = ok ] || fail=1
[ "$fail" = 0 ] || { echo "GATE FAILED"; exit 1; }
for n in 1 2 3; do
  for b in amu c; do
    t1=$(ms "$W/${b}1"); tn=$(ms "$W/${b}$REPS")
    echo "$b round $n: $(echo "($tn-$t1)/($REPS-1)" | bc -l | cut -c1-8) ms per pass (diagnostic)"
  done
done
echo "gate passed (C=$C HW=$HW G=$G REPS=$REPS), build dir $W"
