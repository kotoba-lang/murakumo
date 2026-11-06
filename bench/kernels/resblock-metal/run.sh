#!/bin/sh
# Gate and time an SDXL-style ResBlock run end to end on this Mac's GPU (stage M, slice 8): one Kotoba
# program built by `bin/amu compile --runtime-profile amu-program-metal` dispatches six kernels (GroupNorm+SiLU,
# time-embedding bias, 3x3 conv, GroupNorm+SiLU, 1x1 skip conv, 3x3 conv with bias+residual epilogue) into ONE
# command buffer and one encoder. resblock_gpu_ref.c computes the same block in double precision on the
# parameters the f32 CPU twin draws, and prints the GPU's error and, for scale, the CPU twin's.
# The limit is 2e-2 on |gpu - ref| / max(1, |ref|): the half-precision convolution inputs and weights carry
# ~2^-11 relative error each through 288 and 576 products and two normalisations (set from the f16 GEMM
# slice's ~1e-2 scale for K=512..640, not tuned). Mutants must exceed 0.1:
#   skip connection dropped from the final residual, time-embedding bias dropped, GroupNorm affine per group.
# ms is the best of 20 whole-sequence runs, on whatever load the host has: diagnostic only. A GPU host is
# required (not a CI step). Needs cc, perl, awk.
#
#   sh run.sh /path/to/amu-checkout
set -eu
AMU=${1:?amu checkout}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/resblock-metal.XXXXXX")
fail=0
cc -O2 -ffp-contract=off -I"$HERE/.." -DCI=32 -DCO=64 -DH=16 -DW=16 -DG=32 -DTE=64 -o "$WK/ref" "$HERE/resblock_gpu_ref.c" -lm
build() { # name [from to]
  sed -e "s/@REPS@/20/g" "$HERE/resblock_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${2:-}" ]; then
    FROM=$2 TO=$3 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
}
errs() { # name -> "gpu_err cpu_err"
  "$WK/$1" > /dev/null 2> "$WK/$1.err" || true
  head -c 65536 "$WK/$1.err" > "$WK/$1.f32"; tail -c +65537 "$WK/$1.err" > "$WK/$1.rep"
  "$WK/ref" < "$WK/$1.f32" | awk '{print $4, $6}'
}
build rb
set -- $(errs rb); ge=$1; ce=$2
ok=$(awk -v e="$ge" 'BEGIN{print (e+0 <= 0.02) ? 1 : 0}'); [ "$ok" = 1 ] || fail=1
ns=$(sed -n 's/.*best \([0-9]*\) total.*/\1/p' "$WK/rb.rep")
echo "ResBlock 32->64 16x16 GroupNorm 32: gpu_err=$ge (limit 0.02) cpu_f32_err=$ce $([ "$ok" = 1 ] && echo ok || echo FAIL) best=$(awk -v n="$ns" 'BEGIN{printf "%.2f", n/1e6}') ms for 6 dispatches"
mut() { # label from to
  build "m$1" "$2" "$3"; me=$(errs "m$1" | awk '{print $1}')
  bad=$(awk -v e="$me" 'BEGIN{print (e+0 > 0.1) ? 1 : 0}')
  [ "$bad" = 1 ] && echo "mutant [$1]: error $me (detected)" || { echo "mutant [$1]: error $me NOT detected"; fail=1; }
}
mut skip 'if (MODE == 2u) v += Res[m * NP + p];' 'if (MODE == 9u) v += Res[m * NP + p];'
mut tembias 'O[c] = acc + C1[c];' 'O[c] = C1[c];'
mut gnaffine 'uint ch = g * (C / NG) + i / HW;' 'uint ch = g;'
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
