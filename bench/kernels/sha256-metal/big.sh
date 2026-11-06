#!/bin/sh
# Gate SHA-256 of a multi-gigabyte file (crossing 2^32 bytes, so the 64-bit bit-length field and the offset type are
# exercised): a 4.5 GiB file of a random 64 MiB block repeated 72 times plus a 3-byte tail; `shasum -a 256` is the
# oracle. The file is deleted afterwards. Needs ~4.5 GiB free. Not a CI step.
#
#   sh big.sh /path/to/amu-checkout
set -eu
AMU=${1:?amu checkout}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/sha256-big.XXXXXX")
trap 'rm -rf "$WK"' EXIT
python3 -I - "$WK/big" <<'PY'
import os, sys
blk = os.urandom(64 << 20)
with open(sys.argv[1], "wb") as f:
    for _ in range(72):
        f.write(blk)
    f.write(b"xyz")
PY
sed -e "s#@PATH@#$WK/big#" "$HERE/sha256_metal.kotoba.in" > "$WK/big.kotoba"
"$AMU/bin/amu" compile "$WK/big.kotoba" --runtime-profile amu-program-metal -o "$WK/bigx" >/dev/null
echo "size: $(wc -c < "$WK/big") bytes"
t0=$(perl -MTime::HiRes=time -e 'print time')
got=$("$WK/bigx" 2>&1 >/dev/null | tr -d '\n')
t1=$(perl -MTime::HiRes=time -e 'print time')
want=$(shasum -a 256 "$WK/big" | cut -d' ' -f1)
echo "got  $got"; echo "want $want"
echo "time: $(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.1f", b-a}') s"
[ "$got" = "$want" ] && echo "big gate passed" || { echo "BIG GATE FAILED"; exit 1; }
