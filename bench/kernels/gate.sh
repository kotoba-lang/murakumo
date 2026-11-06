#!/bin/sh
# Generic gate for a bench/kernels/<name>/ f32 kernel built two ways on one Mac:
#   amu-macos  kernel.kotoba.in compiled by the Amu selfhost Mac CLI (bin/amu)
#   c-O2       kernel.c, host cc, -ffp-contract=off
# The kernel directory holds kernel.kotoba.in (placeholders @NAME@), kernel.c
# (parameters as -DNAME=value, plus -DREPS), oracle.c (an f64 reference that
# prints "elements N max_abs_err X ..."), and conf, a shell fragment setting
#   PARAMS="A=1 B=2"   default shape
#   TOL=1e-4           largest accepted max_abs_err against the oracle
#   MUT_FROM / MUT_TO  an exact text replacement that must change the checksum
# Gate: checksums equal at REPS=1 and REPS=N, the mutant differs, the oracle
# error is within TOL. Timing is (t[REPS]-t[1])/(REPS-1), diagnostic only.
# Local measurement; not a CI step. Needs cc, perl, bc.
#
#   sh gate.sh <kernel-dir> <amu-checkout> ["A=1 B=2"] [REPS]
set -eu
KD=$(cd "${1:?kernel dir}" && pwd); AMU=${2:?amu checkout}
. "$KD/conf"
[ -n "${3:-}" ] && PARAMS=$3
# GATE_MUT_FROM / GATE_MUT_TO replace the conf mutation (needed when a shape makes it a no-op)
[ -n "${GATE_MUT_FROM:-}" ] && { MUT_FROM=$GATE_MUT_FROM; MUT_TO=$GATE_MUT_TO; }
REPS=${4:-${REPS:-10}}
WK=${TMPDIR:-/tmp}/kgate-$(basename "$KD")-$$; mkdir -p "$WK"
CLI="$AMU/bin/amu"
DEFS=""; SEDS=""
for kv in $PARAMS; do k=${kv%%=*}; v=${kv#*=}; DEFS="$DEFS -D$k=$v"; SEDS="$SEDS -e s/@$k@/$v/g"; done
subst() { sed $SEDS -e "s/@REPS@/$1/g" "$KD/kernel.kotoba.in"; }
ms() { s=$(perl -MTime::HiRes=time -e 'print time'); "$@" >/dev/null; e=$(perl -MTime::HiRes=time -e 'print time'); echo "($e-$s)*1000" | bc -l; }
for r in 1 "$REPS"; do
  subst "$r" > "$WK/k$r.kotoba"; "$CLI" compile "$WK/k$r.kotoba" -o "$WK/amu$r" >/dev/null
  cc -O2 -ffp-contract=off -I"$KD/.." $DEFS -DREPS="$r" -o "$WK/c$r" "$KD/kernel.c" -lm
done
MUT_FROM="$MUT_FROM" MUT_TO="$MUT_TO" perl -0pe 's/\Q$ENV{MUT_FROM}\E/$ENV{MUT_TO}/ or die "mutation did not apply\n"' "$WK/k1.kotoba" > "$WK/mut.kotoba"
"$CLI" compile "$WK/mut.kotoba" -o "$WK/mut" >/dev/null
cc -O2 -ffp-contract=off -I"$KD/.." $DEFS -o "$WK/oracle" "$KD/oracle.c" -lm
fail=0
for r in 1 "$REPS"; do
  a=$("$WK/amu$r"); c=$("$WK/c$r")
  [ "$a" = "$c" ] && echo "reps=$r checksum equal: $a" || { echo "reps=$r MISMATCH amu=$a c=$c"; fail=1; }
done
m=$("$WK/mut" 2>/dev/null || echo TRAP); c1=$("$WK/c1")
[ "$m" != "$c1" ] && echo "mutant differs: $m != $c1" || { echo "mutant NOT detected"; fail=1; }
echo "oracle: $("$WK/oracle")"
ok=$("$WK/oracle" | awk -v t="$TOL" '{print ($4<=t)?"ok":"FAIL"}'); echo "abs err <= $TOL: $ok"; [ "$ok" = ok ] || fail=1
[ "$fail" = 0 ] || { echo "GATE FAILED"; exit 1; }
for n in 1 2 3; do
  for b in amu c; do
    t1=$(ms "$WK/${b}1"); tn=$(ms "$WK/${b}$REPS")
    echo "$b round $n: $(echo "($tn-$t1)/($REPS-1)" | bc -l | cut -c1-8) ms per pass (diagnostic)"
  done
done
echo "gate passed ($PARAMS REPS=$REPS)"
