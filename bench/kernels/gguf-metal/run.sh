#!/bin/sh
# Gate the GGUF reader on the Metal route (E1, first slice S1): a Kotoba program built by
# `bin/amu compile --runtime-profile amu-program-metal` checks the file's SHA-256 against the pinned digest
# FIRST (one flipped character refuses the run before any tensor is read, the same negative control as the
# pipeline-metal gate), then parses the GGUF header into a tensor catalog (name dtype shape begin end in file
# order, tensor count, KV count and one `kv name type value` line per KV; f32 values print as raw bits so the
# oracle is exact), reads the data region into a Metal buffer and folds it. make_fixture.py (Python 3 stdlib
# only, a local oracle) writes a synthetic file of 290 tensors (E1 inventory names; F32, Q8_0) and, for the same
# file, prints what the Kotoba program must print. KV pin expectations (arch qwen2, block counts, head counts,
# rope base, rms eps, tokenizer ids and array lengths) are asserted by line-for-line equality with that oracle,
# which embeds them. Gate: the Kotoba output equals the expectation line for line; a flipped weight byte changes
# the checksum; a truncated copy, a copy with a bad magic, and a copy whose first tensor's shape disagrees with
# its offsets are refused (a message, a trap, no checksum); a digest that differs in one character refuses the
# run before any tensor line is printed. Not covered: dtypes other than F32 and Q8_0, alignments other than 32,
# headers over 64 MiB, names with backslash escapes or non-UTF-8 bytes,
# data regions not a multiple of 8 bytes. A GPU host is required (not a CI step).
#
#   sh run.sh /path/to/amu-checkout [python3]
set -eu
AMU=${1:?amu checkout}; PY=${2:-python3}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/gguf-metal.XXXXXX")
fail=0
"$PY" "$HERE/make_fixture.py" write "$WK/fixture.gguf"
"$PY" "$HERE/make_fixture.py" expect "$WK/fixture.gguf" > "$WK/expect.txt"
shasum -a 256 "$WK/fixture.gguf" | cut -c1-64 > "$WK/fixture.sha256"
lastc=$(cut -c64 "$WK/fixture.sha256"); [ "$lastc" = 0 ] && flip=1 || flip=0
printf "%s%s" "$(cut -c1-63 "$WK/fixture.sha256")" "$flip" > "$WK/bad.sha256"
build() { # name path expect [from to]
  sed -e "s#@PATH@#$2#;s#@EXPECT@#$3#" "$HERE/gguf_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${4:-}" ]; then
    FROM=$4 TO=$5 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
}
build rd "$WK/fixture.gguf" "$WK/fixture.sha256"
"$WK/rd" > /dev/null 2> "$WK/got.txt" || true
if cmp -s "$WK/got.txt" "$WK/expect.txt"; then echo "catalog and checksum equal the Python expectation ($(wc -l < "$WK/expect.txt" | tr -d ' ') lines)"
else echo "MISMATCH"; diff "$WK/expect.txt" "$WK/got.txt" | head -6; fail=1; fi
"$PY" "$HERE/make_fixture.py" corrupt-data "$WK/fixture.gguf" "$WK/mut.gguf"
shasum -a 256 "$WK/mut.gguf" | cut -c1-64 > "$WK/mut.sha256"
build mut "$WK/mut.gguf" "$WK/mut.sha256"
{ "$WK/mut" > /dev/null 2> "$WK/mut.txt"; } 2>/dev/null || true
mc=$(grep checksum "$WK/mut.txt" | awk '{print $NF}'); ec=$(grep checksum "$WK/expect.txt" | awk '{print $NF}')
if [ -z "$mc" ]; then echo "mutant (weight byte flipped): no checksum (NOT detected as a change)"; fail=1
elif [ "$mc" != "$ec" ]; then echo "mutant (weight byte flipped): checksum $mc != $ec (detected)"
else echo "mutant NOT detected"; fail=1; fi
size=$(wc -c < "$WK/fixture.gguf" | tr -d ' ')
head -c $((size - 8)) "$WK/fixture.gguf" > "$WK/truncated.gguf"
shasum -a 256 "$WK/truncated.gguf" | cut -c1-64 > "$WK/truncated.sha256"
build tr "$WK/truncated.gguf" "$WK/truncated.sha256"
{ "$WK/tr" > /dev/null 2> "$WK/tr.txt"; rc=$?; } 2>/dev/null || true
if grep -q checksum "$WK/tr.txt" || [ "$rc" = 0 ]; then echo "truncated file: NOT refused"; fail=1; else echo "truncated file refused (exit $rc): $(grep refused "$WK/tr.txt" | head -1)"; fi
"$PY" "$HERE/make_fixture.py" corrupt-magic "$WK/fixture.gguf" "$WK/badmagic.gguf"
shasum -a 256 "$WK/badmagic.gguf" | cut -c1-64 > "$WK/badmagic.sha256"
build bm "$WK/badmagic.gguf" "$WK/badmagic.sha256"
{ "$WK/bm" > /dev/null 2> "$WK/bm.txt"; rc=$?; } 2>/dev/null || true
if grep -q checksum "$WK/bm.txt" || [ "$rc" = 0 ]; then echo "bad magic: NOT refused"; fail=1; else echo "bad magic refused (exit $rc): $(grep refused "$WK/bm.txt" | head -1)"; fi
"$PY" "$HERE/make_fixture.py" corrupt-shape "$WK/fixture.gguf" "$WK/badshape.gguf"
shasum -a 256 "$WK/badshape.gguf" | cut -c1-64 > "$WK/badshape.sha256"
build bs "$WK/badshape.gguf" "$WK/badshape.sha256"
{ "$WK/bs" > /dev/null 2> "$WK/bs.txt"; rc=$?; } 2>/dev/null || true
if grep -q checksum "$WK/bs.txt" || [ "$rc" = 0 ]; then echo "file with a wrong shape: NOT refused"; fail=1; else echo "file with a wrong shape refused (exit $rc): $(grep refused "$WK/bs.txt" | head -1)"; fi
"$PY" "$HERE/make_fixture.py" write-big "$WK/big.gguf"
"$PY" "$HERE/make_fixture.py" expect "$WK/big.gguf" > "$WK/big-expect.txt"
shasum -a 256 "$WK/big.gguf" | cut -c1-64 > "$WK/big.sha256"
build big "$WK/big.gguf" "$WK/big.sha256"
"$WK/big" > /dev/null 2> "$WK/big-got.txt" || true
if cmp -s "$WK/big-got.txt" "$WK/big-expect.txt"; then echo "file over 16 MiB ($(wc -c < "$WK/big.gguf" | tr -d ' ') bytes): catalog and checksum equal the Python expectation, data read in chunks"
else echo "file over 16 MiB: MISMATCH"; diff "$WK/big-expect.txt" "$WK/big-got.txt" | head -20; fail=1; fi
build mbad "$WK/fixture.gguf" "$WK/bad.sha256"
{ "$WK/mbad" > /dev/null 2> "$WK/mbad.txt"; rc=$?; } 2>/dev/null || true
if grep -q "tensors \|^kv " "$WK/mbad.txt" || [ "$rc" = 0 ]; then echo "digest mismatch: catalog printed (NOT refused)"; fail=1; else echo "digest mismatch: refused before any tensor is read (exit $rc): $(grep refused "$WK/mbad.txt" | head -1)"; fi
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
