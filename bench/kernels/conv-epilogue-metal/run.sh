#!/bin/sh
# Gate and time the generalised implicit-GEMM convolution with epilogues (stage M, slice 4).
#   metal   conv_epilogue_metal.kotoba.in built with `bin/amu compile --runtime-profile amu-program-metal`
#   ref     conv_ref.c, host cc: the same inputs, the convolution exactly in int64, the epilogue in double
# The program writes the raw float32 output to stderr, then a timing line. Modes 0 (none), 1 (+ bias) and
# 2 (+ bias + residual) are exact integers in float32, so the gate demands error exactly 0; mode 3
# (SiLU) allows 1e-4 (|gpu - ref| / max(1, |ref|)). Mutants must change the output:
#   bias index dropped (mode 1), stride dropped from the row coordinate (stride-2 shape),
#   residual indexed without the channel (mode 2).
# GMAC/s = COUT * NP * CIN * KS * KS over the best of 20 dispatches, command-buffer floor included, on
# whatever load the host has: diagnostic only. A GPU host is required (not a CI step). Needs cc, awk, bc.
#
#   sh run.sh /path/to/amu-checkout
set -eu
AMU=${1:?amu checkout}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/conv-epilogue.XXXXXX")
CLI="$AMU/bin/amu"
fail=0
dims() { # CIN COUT H W KS STR -> sets OH OW NP NPP
  PADV=$(( $5 / 2 )); OH=$(( ($3 + 2 * PADV - $5) / $6 + 1 )); OW=$(( ($4 + 2 * PADV - $5) / $6 + 1 ))
  NP=$(( OH * OW )); NPP=$(( (NP + 63) / 64 * 64 ))
}
build() { # name CIN COUT H W KS STR MODE VMASK VOFF [from to]
  n=$1; dims "$2" "$3" "$4" "$5" "$6" "$7"
  sed -e "s/@CIN@/$2/g;s/@COUT@/$3/g;s/@H@/$4/g;s/@W@/$5/g;s/@KS@/$6/g;s/@STR@/$7/g;s/@MODE@/$8/g;s/@OH@/$OH/g;s/@OW@/$OW/g;s/@NP@/$NP/g;s/@NPP@/$NPP/g;s/@REPS@/20/g;s/@VMASK@/$9/g;s/@VOFF@/${10}/g" "$HERE/conv_epilogue_metal.kotoba.in" > "$WK/$n.kotoba"
  if [ -n "${11:-}" ]; then
    FROM=${11} TO=${12} perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$n.kotoba"
  fi
  "$CLI" compile "$WK/$n.kotoba" --runtime-profile amu-program-metal -o "$WK/$n" >/dev/null
}
errof() { # name CIN COUT H W KS STR MODE VMASK VOFF -> "max_err max_abs_ref"
  n=$1; dims "$2" "$3" "$4" "$5" "$6" "$7"; bytes=$(( $3 * NPP * 4 ))
  "$WK/$n" > /dev/null 2> "$WK/$n.err" || true
  head -c "$bytes" "$WK/$n.err" > "$WK/$n.f32"; tail -c +$((bytes + 1)) "$WK/$n.err" > "$WK/$n.rep"
  cc -O2 -DCIN="$2" -DCOUT="$3" -DH="$4" -DW="$5" -DKS="$6" -DSTR="$7" -DMODE="$8" -DVMASK="$9" -DVOFF="${10}" -o "$WK/ref_$n" "$HERE/conv_ref.c" -lm
  "$WK/ref_$n" < "$WK/$n.f32" | awk '{print $4, $6}'
}
case_() { # CIN COUT H W KS STR MODE VMASK VOFF
  id="$1_$2_$3x$4_k$5s$6m$7"; build "c$id" "$@"
  set -- "$@" $(errof "c$id" "$@"); err=${10}; ref=${11}
  tol=0; [ "$7" = 3 ] && tol=0.0001
  ok=$(awk -v e="$err" -v t="$tol" 'BEGIN{print (e+0 <= t+0) ? 1 : 0}')
  [ "$ok" = 1 ] || fail=1
  ns=$(sed -n 's/.*best \([0-9]*\) total.*/\1/p' "$WK/c$id.rep"); dims "$1" "$2" "$3" "$4" "$5" "$6"
  echo "CIN=$1 COUT=$2 ${3}x$4 k$5 s$6 mode$7 max_err=$err (limit $tol) $([ "$ok" = 1 ] && echo ok || echo FAIL) best_ns=$ns GMAC/s=$(echo "scale=1; $2*$NP*$1*$5*$5 / $ns" | bc)"
}
mutant() { # label from to CIN COUT H W KS STR MODE VMASK VOFF
  lab=$1; from=$2; to=$3; shift 3
  build "m$lab" "$@" "$from" "$to"
  merr=$(errof "m$lab" "$@" | awk '{print $1}')
  bad=$(awk -v e="$merr" 'BEGIN{print (e+0 > 0.001) ? 1 : 0}')
  [ "$bad" = 1 ] && echo "mutant [$lab]: error $merr (detected)" || { echo "mutant [$lab]: error $merr NOT detected"; fail=1; }
}
case_ 32 64 32 32 3 1 0 127 64
case_ 32 64 32 32 3 1 1 127 64
case_ 64 128 24 40 1 1 2 127 64
case_ 64 128 32 32 3 2 1 127 64
case_ 4 64 48 48 3 1 1 127 64
case_ 64 4 48 48 3 1 1 127 64
case_ 32 64 10 10 3 1 3 15 8
mutant bias 'v += Bias[m];' 'v += Bias[0];' 32 64 32 32 3 1 1 127 64
mutant stride 'int((p / OW) * STR)' 'int(p / OW)' 64 128 32 32 3 2 1 127 64
mutant residual 'v += Res[m * NP + p];' 'v += Res[p];' 64 128 24 40 1 1 2 127 64
case_ 320 320 48 48 3 1 3 15 8
case_ 640 640 48 48 3 1 1 15 8
case_ 640 640 48 48 3 2 1 15 8
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
