#!/bin/sh
# Gate and time the normalisation, activation and data-movement kernels (stage M, slice 5) on this
# Mac's GPU: GroupNorm+SiLU, LayerNorm, GEGLU, nearest 2x upsample, sinusoidal timestep embedding,
# residual add. One Kotoba program per case (`bin/amu compile --runtime-profile amu-program-metal`),
# the MSL for each operation in msl/<op>.msl. The reference (ops_ref.c) rebuilds the same inputs and
# computes the operation in double with libm; the error is |gpu - ref| / max(1, |ref|). Limits:
#   upsample and add 0 (exact), groupnorm / layernorm / geglu 1e-5 .. 1e-4, timestep 1e-3 (the
#   float32 argument t * freq reaches ~1000 rad, so sin and cos inherit its rounding).
# Each operation has a mutant that must raise the error above 1e-2. GB/s is the bytes the kernel reads
# and writes over the best of 20 dispatches, command-buffer floor included, on whatever load the host
# has: diagnostic only. A GPU host is required (not a CI step). Needs cc, perl, awk, bc.
#
#   sh run.sh /path/to/amu-checkout
set -eu
AMU=${1:?amu checkout}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/ops-metal.XXXXXX")
CLI="$AMU/bin/amu"
fail=0
# op name | MSL fragment | "sed placeholder values" | cc defines | N0 N1 N2 NOUT | BYG GX TX | limit | mutant from => to
build() { # name op seds n0 n1 n2 nout byg gx tx [from to]
  n=$1; op=$2; seds=$3
  perl -0pe 'BEGIN{ local $/; open F, "<", "'"$HERE"'/msl/'"$op"'.msl"; $f=<F>; close F } s/\@OPSRC\@/$f/' "$HERE/ops_metal.kotoba.in" \
    | sed -e "s/@OP@/$op/g;s/@N0@/$4/g;s/@N1@/$5/g;s/@N2@/$6/g;s/@NOUT@/$7/g;s/@BYG@/$8/g;s/@GX@/$9/g;s/@TX@/${10}/g;s/@REPS@/20/g;s/@VMASK@/127/g;s/@VOFF@/64/g" $seds > "$WK/$n.kotoba"
  if [ -n "${11:-}" ]; then
    FROM=${11} TO=${12} perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$n.kotoba"
  fi
  "$CLI" compile "$WK/$n.kotoba" --runtime-profile amu-program-metal -o "$WK/$n" >/dev/null
}
errof() { # name defines nout -> "max_err ref"
  n=$1; bytes=$(( $3 * 4 ))
  "$WK/$n" > /dev/null 2> "$WK/$n.err" || true
  head -c "$bytes" "$WK/$n.err" > "$WK/$n.f32"; tail -c +$((bytes + 1)) "$WK/$n.err" > "$WK/$n.rep"
  cc -O2 $2 -o "$WK/ref_$n" "$HERE/ops_ref.c" -lm
  "$WK/ref_$n" < "$WK/$n.f32" | awk '{print $4, $6}'
}
case_() { # label op "seds" "defines" n0 n1 n2 nout byg gx tx limit bytes
  lab=$1; build "$lab" "$2" "$3" "$5" "$6" "$7" "$8" "$9" "${10}" "${11}"
  set -- "$@" $(errof "$lab" "$4" "$8"); err=${14}; ref=${15}
  ok=$(awk -v e="$err" -v t="${12}" 'BEGIN{print (e+0 <= t+0) ? 1 : 0}')
  [ "$ok" = 1 ] || fail=1
  ns=$(sed -n 's/.*best \([0-9]*\) total.*/\1/p' "$WK/$lab.rep")
  echo "$lab max_err=$err (limit ${12}) $([ "$ok" = 1 ] && echo ok || echo FAIL) best_ns=$ns GB/s=$(echo "scale=1; ${13} / $ns" | bc)"
}
mutant() { # label op "seds" "defines" n0 n1 n2 nout byg gx tx from to
  build "m$1" "$2" "$3" "$5" "$6" "$7" "$8" "$9" "${10}" "${11}" "${12}" "${13}"
  merr=$(errof "m$1" "$4" "$8" | awk '{print $1}')
  bad=$(awk -v e="$merr" 'BEGIN{print (e+0 > 0.01) ? 1 : 0}')
  [ "$bad" = 1 ] && echo "mutant [$1]: error $merr (detected)" || { echo "mutant [$1]: error $merr NOT detected"; fail=1; }
}
GN_S="-e s/@C@/64/g -e s/@HW@/576/g -e s/@G@/8/g"; GN_D="-DOP_GN -DC=64 -DHW=576 -DG=8"
LN_S="-e s/@N@/640/g"; LN_D="-DOP_LN -DM=96 -DN=640"
GE_S="-e s/@N@/320/g"; GE_D="-DOP_GEGLU -DM=64 -DN=320"
UP_S="-e s/@H@/24/g -e s/@W@/40/g"; UP_D="-DOP_UP -DC=32 -DH=24 -DW=40"
TS_S="-e s/@DIM@/320/g -e s/@TS@/16/g"; TS_D="-DOP_TS -DDIM=320 -DNT=64 -DTS=16"
AD_S=""; AD_D="-DOP_ADD -DN=100000"
case_ groupnorm_silu groupnorm_silu "$GN_S" "$GN_D" $((64*576)) 64 64 $((64*576)) 1 8 128 0.0001 $((64*576*8))
case_ layernorm layernorm "$LN_S" "$LN_D" $((96*640)) 640 640 $((96*640)) 1 96 128 0.0001 $((96*640*8))
case_ geglu geglu "$GE_S" "$GE_D" $((64*640)) 4 4 $((64*320)) 0 $((64*320)) 64 0.00001 $((64*960*4))
case_ upsample upsample "$UP_S" "$UP_D" $((32*24*40)) 4 4 $((32*4*24*40)) 0 $((32*4*24*40)) 64 0 $((32*24*40*5*4))
case_ timestep timestep "$TS_S" "$TS_D" 4 4 4 $((64*320)) 0 $((64*320)) 64 0.001 $((64*320*4))
case_ add add "$AD_S" "$AD_D" 100000 4 100000 100000 0 100000 64 0 $((100000*12))
mutant groupnorm_channel groupnorm_silu "$GN_S" "$GN_D" $((64*576)) 64 64 $((64*576)) 1 8 128 'uint ch = g * (C / NG) + i / HW;' 'uint ch = g;'
mutant layernorm_affine layernorm "$LN_S" "$LN_D" $((96*640)) 640 640 $((96*640)) 1 96 128 '* Ga[i] + Be[i];' '* Be[i] + Ga[i];'
mutant geglu_gate geglu "$GE_S" "$GE_D" $((64*640)) 4 4 $((64*320)) 0 $((64*320)) 64 'float a = X[r * 2u * NN + c];' 'float a = X[r * 2u * NN + NN + c];'
mutant upsample_index upsample "$UP_S" "$UP_D" $((32*24*40)) 4 4 $((32*4*24*40)) 0 $((32*4*24*40)) 64 'y / 2u' 'y / 3u'
mutant timestep_swap timestep "$TS_S" "$TS_D" 4 4 4 $((64*320)) 0 $((64*320)) 64 '(j < half_) ? precise::cos(arg) : precise::sin(arg)' '(j < half_) ? precise::sin(arg) : precise::cos(arg)'
mutant add_operand add "$AD_S" "$AD_D" 100000 4 100000 100000 0 100000 64 'A[i] + B2[i]' 'A[i] + B1[i]'
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
