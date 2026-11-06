#!/bin/sh
# Gate the safetensors reader on the Metal route (I3a, first slice): a Kotoba program built by
# `bin/amu compile --runtime-profile amu-program-metal` opens a safetensors file with libSystem's open and
# pread, parses the JSON header into a tensor catalog, reads the data region into a Metal buffer and folds it.
# make_fixture.py (Python 3 with the safetensors and numpy packages, a local oracle only) writes a synthetic file
# of 28 tensors (SDXL-style names; F16, F32, F64, I64, I8; a scalar) and, for the same file, prints what the
# Kotoba program must print, from the header parsed by Python's json module and the data region folded by Python.
# Gate: the Kotoba output equals the expectation line for line (catalog in file order, tensor count, data-region
# size and checksum); a one-byte shift of the data offset (mutant) changes the checksum; a truncated copy of the
# file, and a copy whose first tensor's shape disagrees with its offsets, are refused (a message, a trap, no checksum). Not covered: sha256 of the file, BF16 shapes beyond
# the header (the parser does not interpret dtypes), files over 4 GiB read in one pread (untested), names with
# backslash escapes. A GPU host is required (not a CI step).
#
#   sh run.sh /path/to/amu-checkout [python3]
set -eu
AMU=${1:?amu checkout}; PY=${2:-python3.11}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/safetensors-metal.XXXXXX")
fail=0
"$PY" "$HERE/make_fixture.py" write "$WK/fixture.safetensors"
"$PY" "$HERE/make_fixture.py" expect "$WK/fixture.safetensors" > "$WK/expect.txt"
build() { # name path [from to]
  sed -e "s#@PATH@#$2#" "$HERE/safetensors_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${3:-}" ]; then
    FROM=$3 TO=$4 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
}
build rd "$WK/fixture.safetensors"
"$WK/rd" > /dev/null 2> "$WK/got.txt" || true
if cmp -s "$WK/got.txt" "$WK/expect.txt"; then echo "catalog and checksum equal the Python expectation ($(wc -l < "$WK/expect.txt" | tr -d ' ') lines)"
else echo "MISMATCH"; diff "$WK/expect.txt" "$WK/got.txt" | head -6; fail=1; fi
build mut "$WK/fixture.safetensors" '(+ 8 hlen) 0)' '(+ 9 hlen) 0)'
{ "$WK/mut" > /dev/null 2> "$WK/mut.txt"; } 2>/dev/null || true
mc=$(grep checksum "$WK/mut.txt" | awk '{print $NF}'); ec=$(grep checksum "$WK/expect.txt" | awk '{print $NF}')
if [ -z "$mc" ]; then echo "mutant (data offset shifted by one): the short read traps, no checksum (detected)"
elif [ "$mc" != "$ec" ]; then echo "mutant (data offset shifted by one): checksum $mc != $ec (detected)"
else echo "mutant NOT detected"; fail=1; fi
size=$(wc -c < "$WK/fixture.safetensors" | tr -d ' ')
head -c $((size - 5)) "$WK/fixture.safetensors" > "$WK/truncated.safetensors"
build tr "$WK/truncated.safetensors"
{ "$WK/tr" > /dev/null 2> "$WK/tr.txt"; rc=$?; } 2>/dev/null || true
if grep -q checksum "$WK/tr.txt" || [ "$rc" = 0 ]; then echo "truncated file: NOT refused"; fail=1; else echo "truncated file refused (exit $rc): $(grep refused "$WK/tr.txt")"; fi
"$PY" "$HERE/make_fixture.py" corrupt-shape "$WK/fixture.safetensors" "$WK/badshape.safetensors"
build bs "$WK/badshape.safetensors"
{ "$WK/bs" > /dev/null 2> "$WK/bs.txt"; rc=$?; } 2>/dev/null || true
if grep -q checksum "$WK/bs.txt" || [ "$rc" = 0 ]; then echo "file with a wrong shape: NOT refused"; fail=1; else echo "file with a wrong shape refused (exit $rc): $(grep refused "$WK/bs.txt")"; fi
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
