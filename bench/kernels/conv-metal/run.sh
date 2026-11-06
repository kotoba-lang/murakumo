#!/bin/sh
# Gate and time the implicit-GEMM 3x3 convolution (stage M, slice 2) on this Mac's GPU.
#   metal   conv_metal.kotoba.in built with `bin/amu compile --runtime-profile amu-program-metal`
#   c       conv.c, host cc (an exact integer twin)
# Gate shapes use small integers (-64..63): every product and partial sum is an exact float32
# integer, so the checksum must equal the twin's whatever order the matrix units add in, and a
# mutant (the kernel's row offset dropped from the padding test) must change it. Timing shapes
# are SDXL-like and use values -8..7 so their sums stay exact too (sum of K*64 < 2^24), so they
# are also checked against the twin. GMAC/s is the best of 20 dispatches, command-buffer floor
# included, on whatever load the host has: diagnostic only. A GPU host is required (not a CI step).
#
#   sh run.sh /path/to/amu-checkout
set -eu
AMU=${1:?amu checkout}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/conv-metal.XXXXXX")
CLI="$AMU/bin/amu"
build() { # name CIN COUT H W reps vmask voff [from to]
  n=$1; sed -e "s/@CIN@/$2/g;s/@COUT@/$3/g;s/@H@/$4/g;s/@W@/$5/g;s/@REPS@/$6/g;s/@VMASK@/$7/g;s/@VOFF@/$8/g" "$HERE/conv_metal.kotoba.in" > "$WK/$n.kotoba"
  if [ -n "${9:-}" ]; then
    FROM=$9 TO=${10} perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$n.kotoba"
  fi
  "$CLI" compile "$WK/$n.kotoba" --runtime-profile amu-program-metal -o "$WK/$n" >/dev/null
}
fail=0; first=1
run_shape() { # CIN COUT H W vmask voff
  id="$1_$2_$3_$4_$5"
  build "g$id" "$1" "$2" "$3" "$4" 20 "$5" "$6"
  cc -O2 -DCIN="$1" -DCOUT="$2" -DH="$3" -DW="$4" -DVMASK="$5" -DVOFF="$6" -o "$WK/c$id" "$HERE/conv.c"
  c=$("$WK/c$id"); m=$("$WK/g$id" 2> "$WK/err$id"); ns=$(sed -n 's/.*best \([0-9]*\) total.*/\1/p' "$WK/err$id")
  eq=no; [ "$c" = "$m" ] && eq=yes || fail=1
  echo "CIN=$1 COUT=$2 ${3}x$4 vals=-$6..$(( $5 - $6 )) twin=$c metal=$m equal=$eq best_ns=$ns GMAC/s=$(echo "scale=1; $2*$3*$4*$1*9 / $ns" | bc)"
  if [ "$first" = 1 ]; then
    first=0
    build "mut$id" "$1" "$2" "$3" "$4" 1 "$5" "$6" 'int(p / IW) + int(kr / 3u) - 1' 'int(p / IW) + int(kr % 3u) - 1'
    mm=$("$WK/mut$id" 2>/dev/null || echo TRAP)
    [ "$mm" != "$c" ] && echo "mutant (wrong row offset) differs: $mm != $c" || { echo "mutant NOT detected"; fail=1; }
  fi
}
run_shape 32 64 32 32 127 64
run_shape 64 128 24 40 127 64
run_shape 320 320 48 48 15 8
run_shape 640 640 24 24 15 8
run_shape 640 640 48 48 15 8
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
