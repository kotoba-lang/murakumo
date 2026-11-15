#!/bin/sh
# Gate engine-plan E1 (AWAI-2851): Qwen2.5-0.5B Q8_0 greedy decode on the Apple GPU from a Kotoba program built by
# `bin/amu compile --runtime-profile amu-program-metal`. The model file must be the pinned GGUF
# (bartowski/Qwen2.5-0.5B-Instruct-GGUF, Qwen2.5-0.5B-Instruct-Q8_0.gguf, sha256 below).
#
# Rows:
#   exact          prompt 760 6511 314 9338 369 -> greedy 314 746 600 220 (the 2026-10-19 oracle; ref_forward.py, numpy,
#                  is a local re-check of the same continuation and is not run here)
#   kernel-mutant  the RoPE rotation with one sign flipped changes the tokens
#   weight-mutant  a copy of the file with the sign bit of one fp16 scale in token 314's embedding row flipped
#                  (its own digest pinned) changes the tokens
#   digest         a pinned digest that differs in one character refuses before any token
#   wrong-model    the synthetic S1 fixture (Qwen2 KVs, scaled-down shapes) is refused by the shape check
# Timing on stderr (`e1-ns ...`) is a diagnostic, not a benchmark. A Mac GPU host is required (not a CI step).
#
#   sh run.sh /path/to/amu-checkout /path/to/Qwen2.5-0.5B-Instruct-Q8_0.gguf [python3]
set -eu
AMU=${1:?amu checkout}; MODEL=${2:?model gguf}; PY=${3:-python3}
PIN=25130a98aa782284a7dabea0c23245b2fd371ed47244e79d78b8ec23245fdf96
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/qwen2-metal.XXXXXX")
fail=0
have=$(shasum -a 256 "$MODEL" | cut -c1-64)
[ "$have" = "$PIN" ] || { echo "model digest $have is not the pinned $PIN"; exit 1; }
printf "%s" "$PIN" > "$WK/model.sha256"
build() { # name model digest-file [from to]
  sed -e "s#@PATH@#$2#g;s#@EXPECT@#$3#g;s#@IDS@#760 6511 314 9338 369#g;s#@STEPS@#4#g" "$HERE/qwen2_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${4:-}" ]; then
    FROM=$4 TO=$5 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
}
runit() { { "$WK/$1" > /dev/null 2> "$WK/$1.txt"; rc=$?; } 2>/dev/null || true; }
WANT="greedy 314 746 600 220"

build ok "$MODEL" "$WK/model.sha256"
rc=0; runit ok
got=$(grep '^greedy' "$WK/ok.txt" || true)
if [ "$rc" = 0 ] && [ "$got" = "$WANT" ]; then echo "exact: $got ($(grep '^e1-ns' "$WK/ok.txt"))"
else echo "exact: FAILED (exit $rc): $(head -3 "$WK/ok.txt")"; fail=1; fi

build km "$MODEL" "$WK/model.sha256" "y[j + 32] = a * s + b * c;" "y[j + 32] = a * s - b * c;"
rc=0; runit km
got=$(grep '^greedy' "$WK/km.txt" || true)
if [ -n "$got" ] && [ "$got" != "$WANT" ]; then echo "kernel-mutant (RoPE sign): $got (detected)"
else echo "kernel-mutant NOT detected (exit $rc): $got"; fail=1; fi

"$PY" "$HERE/flip_scale.py" "$MODEL" "$WK/mut.gguf"
shasum -a 256 "$WK/mut.gguf" | cut -c1-64 > "$WK/mut.sha256"
build wm "$WK/mut.gguf" "$WK/mut.sha256"
rc=0; runit wm
got=$(grep '^greedy' "$WK/wm.txt" || true)
if [ -n "$got" ] && [ "$got" != "$WANT" ]; then echo "weight-mutant (one scale sign in token 314's row): $got (detected)"
else echo "weight-mutant NOT detected (exit $rc): $got"; fail=1; fi
rm -f "$WK/mut.gguf"

last=$(printf "%s" "$PIN" | cut -c64); [ "$last" = 0 ] && flip=1 || flip=0
printf "%s%s" "$(printf "%s" "$PIN" | cut -c1-63)" "$flip" > "$WK/bad.sha256"
build bd "$MODEL" "$WK/bad.sha256"
rc=0; runit bd
if [ "$rc" != 0 ] && ! grep -q '^greedy' "$WK/bd.txt"; then echo "digest: one character off refuses before any token (exit $rc)"
else echo "digest: NOT refused (exit $rc)"; fail=1; fi

"$PY" "$HERE/../gguf-metal/make_fixture.py" write "$WK/fixture.gguf"
shasum -a 256 "$WK/fixture.gguf" | cut -c1-64 > "$WK/fixture.sha256"
build wr "$WK/fixture.gguf" "$WK/fixture.sha256"
rc=0; runit wr
if [ "$rc" != 0 ] && ! grep -q '^greedy' "$WK/wr.txt"; then echo "wrong-model: refused (exit $rc): $(grep refused "$WK/wr.txt" | head -1)"
else echo "wrong-model: NOT refused (exit $rc)"; fail=1; fi

[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
