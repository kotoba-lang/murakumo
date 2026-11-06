#!/bin/sh
# Gate SHA-256 of a file in Kotoba on the Metal route (digest-before-execute): sha256_metal.kotoba.in built with
# `bin/amu compile --runtime-profile amu-program-metal` reads a file with libSystem's open / pread (the profile gives the
# program libSystem through dyld, amu ADR 0405) and prints the hex digest to stderr. `shasum -a 256` is the oracle.
# Files: empty, "abc", sizes around the padding boundaries (55, 56, 63, 64, 65, 119, 120, 127, 128 bytes), a million
# 'a', and 5 MiB of random bytes (which crosses the 1 MiB read chunk). A mutant (one round constant changed) must
# give a different digest. The throughput line is the wall time of one run over the 5 MiB file, process start and
# compile excluded only by being a second run: diagnostic, on whatever load the host has. A Metal-route host is
# required (not a CI step). Needs shasum, head, dd, awk.
#
#   sh run.sh /path/to/amu-checkout
set -eu
AMU=${1:?amu checkout}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/sha256-metal.XXXXXX")
fail=0
build() { # name path [from to]
  sed -e "s#@PATH@#$2#" "$HERE/sha256_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${3:-}" ]; then
    FROM=$3 TO=$4 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
}
check() { # label file
  build "h$1" "$2"
  got=$("$WK/h$1" 2>&1 >/dev/null | tr -d '\n'); want=$(shasum -a 256 "$2" | cut -d' ' -f1)
  [ "$got" = "$want" ] && echo "$1: $want ok" || { echo "$1: MISMATCH got=$got want=$want"; fail=1; }
}
: > "$WK/empty"; check empty "$WK/empty"
printf abc > "$WK/abc"; check abc "$WK/abc"
for n in 55 56 63 64 65 119 120 127 128; do head -c $n /dev/zero | tr '\0' 'x' > "$WK/p$n"; check "len$n" "$WK/p$n"; done
head -c 1000000 /dev/zero | tr '\0' 'a' > "$WK/million"; check million-a "$WK/million"
head -c 5242880 /dev/urandom > "$WK/rand5m"; check random-5MiB "$WK/rand5m"
build mut "$WK/abc" '1116352408' '1116352409'
mg=$("$WK/mut" 2>&1 >/dev/null | tr -d '\n'); wg=$(shasum -a 256 "$WK/abc" | cut -d' ' -f1)
[ "$mg" != "$wg" ] && echo "mutant (round constant 0x428a2f98 changed): digest differs (detected)" || { echo "mutant NOT detected"; fail=1; }
t0=$(perl -MTime::HiRes=time -e 'print time'); "$WK/hrandom-5MiB" > /dev/null 2>&1; t1=$(perl -MTime::HiRes=time -e 'print time')
echo "throughput: 5 MiB in $(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.2f", b-a}') s = $(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.2f", 5/(b-a)}') MiB/s (diagnostic)"
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
