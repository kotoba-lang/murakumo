#!/bin/sh
# Gate and time the fused non-causal multi-head attention (stage M, slice 3) on this Mac's GPU.
#   metal   attention_metal.kotoba.in built with `bin/amu compile --runtime-profile amu-program-metal`
#   ref     attention_ref.c, host cc: the same inputs in double precision with libm exp
# The program writes the raw float32 output to stderr, then a timing line. exp and the half
# rounding of P make the output approximate, so the gate is a bound, not equality:
#   |error| <= 2e-3 on outputs of magnitude <= 1 (a mutant must exceed 5e-2):
#     scale   the 1/8 score scale replaced by 1 (first shape)
#     mask    padded keys no longer masked in pass 2 (the LK = 77 shape)
# GMAC/s counts the two GEMMs (2 * LQ * LK * 64 * NH) over the best of 20 dispatches,
# command-buffer floor included, on whatever load the host has: diagnostic only.
# A GPU host is required (not a CI step). Needs cc, awk, bc (bc only for the integer GMAC/s).
#
#   sh run.sh /path/to/amu-checkout
set -eu
AMU=${1:?amu checkout}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/attention-metal.XXXXXX")
CLI="$AMU/bin/amu"
TOL=0.002; MUTTOL=0.05
build() { # name LQ LK NH [from to]
  n=$1; sed -e "s/@LQ@/$2/g;s/@LK@/$3/g;s/@NH@/$4/g;s/@REPS@/20/g;s/@VMASK@/127/g;s/@VOFF@/64/g" "$HERE/attention_metal.kotoba.in" > "$WK/$n.kotoba"
  if [ -n "${5:-}" ]; then
    FROM=$5 TO=$6 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$n.kotoba"
  fi
  "$CLI" compile "$WK/$n.kotoba" --runtime-profile amu-program-metal -o "$WK/$n" >/dev/null
}
errof() { # name LQ LK NH  -> prints "max_abs_err max_abs_ref" and sets REPORT
  n=$1; bytes=$(( $2 * $4 * 64 * 4 ))
  "$WK/$n" > /dev/null 2> "$WK/$n.err" || true
  head -c "$bytes" "$WK/$n.err" > "$WK/$n.f32"; tail -c +$((bytes + 1)) "$WK/$n.err" > "$WK/$n.rep"
  cc -O2 -DLQ="$2" -DLK="$3" -DNH="$4" -o "$WK/ref_$2_$3_$4" "$HERE/attention_ref.c" -lm
  "$WK/ref_$2_$3_$4" < "$WK/$n.f32" | awk '{print $4, $6}'
}
fail=0
shape() { # LQ LK NH
  id="$1_$2_$3"; build "a$id" "$1" "$2" "$3"
  set -- $(errof "a$id" "$1" "$2" "$3") "$1" "$2" "$3"; err=$1; ref=$2; LQ=$3; LK=$4; NH=$5
  ns=$(sed -n 's/.*best \([0-9]*\) total.*/\1/p' "$WK/a$id.rep")
  ok=$(awk -v e="$err" -v t="$TOL" 'BEGIN{print (e+0 <= t+0) ? 1 : 0}'); [ "$ok" = 1 ] || fail=1
  echo "LQ=$LQ LK=$LK heads=$NH max_abs_err=$err max_abs_ref=$ref within_$TOL=$([ "$ok" = 1 ] && echo yes || echo NO) best_ns=$ns GMAC/s=$(echo "scale=1; 2*$LQ*$LK*64*$NH / $ns" | bc)"
}
mutant() { # name LQ LK NH from to
  mn=$1; mfrom=$5
  build "$1" "$2" "$3" "$4" "$5" "$6"
  merr=$(errof "$1" "$2" "$3" "$4" | awk '{print $1}')
  bad=$(awk -v e="$merr" -v t="$MUTTOL" 'BEGIN{print (e+0 > t+0) ? 1 : 0}'); [ "$bad" = 1 ] && echo "mutant [$mfrom]: error $merr > $MUTTOL (detected)" || { echo "mutant [$mfrom]: error $merr NOT detected"; fail=1; }
}
shape 256 256 4
shape 256 77 4
mutant mscale 256 256 4 'constant float SCALE = 0.125f;' 'constant float SCALE = 1.0f;'
mutant mmask 256 77 4 '(kb * 32u + c < LK) ? exp(Ssm[idx]' '(true) ? exp(Ssm[idx]'
shape 576 576 20
shape 2304 2304 10
shape 2304 77 10
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
