#!/bin/sh
# Gate AWAI-2938 (Qwen3.5 on the Metal route through the engine library, docs/engine-profiles.md): Qwen3.5-4B Q4_K_M
# greedy decode on the Apple GPU from a Kotoba program built by `bin/amu compile ENTRY --source-path engine/src
# --runtime-profile amu-program-metal`. The model must be the pinned GGUF (unsloth/Qwen3.5-4B-GGUF @ e87f176,
# Qwen3.5-4B-Q4_K_M.gguf, sha256 below).
#
# Rows:
#   exact            prompt 760 6511 314 9338 369 -> 16 greedy tokens equal to llama.cpp b11669's (recorded
#                    2026-11-15, bench/results/2026-11-15-qwen35-oracle; ref_forward35.py, numpy, agrees on the first 6;
#                    neither is run here)
#   (mutant rows compare 16 tokens: a model this size absorbs some errors for the first few)
#   tile-mutant      delta-net value heads mapped to key heads by hv / 2 instead of hv mod 16 changes the tokens
#   conv-mutant      the conv1d reading only the current token (its 3-step state ignored) changes the tokens
#   layer4           after 4 layers (the first gated-attention layer included) the residual's first 6 values at the last
#                    prompt position are within 2e-4 of the oracle's (ref_forward35.py --layers 4, pinned below)
#   rope-mutant      the gated-attention RoPE with doubled frequencies moves that residual by more (the greedy tokens
#                    of this short prompt do not change, so this row is numeric)
#   gate-mutant      the attention output without its sigmoid gate changes the tokens
#   q6k-mutant       one scale index of the Q6_K matvec off by one changes the tokens
#   q4k-mutant       the 16-byte Q4_K/Q5_K matvec with the scales of its low- and high-nibble sub-blocks swapped changes
#                    the tokens
#   digest           a pinned digest that differs in one character refuses before any token
#   wrong-model      the synthetic S1 fixture (architecture qwen2) is refused by the qwen35 profile
# Timing on stderr (`q35-ns ...`) is a diagnostic, not a benchmark. A Mac GPU host is required (not a CI step).
#
#   sh run.sh /path/to/amu-checkout /path/to/Qwen3.5-4B-Q4_K_M.gguf [python3]
set -eu
AMU=${1:?amu checkout}; MODEL=${2:?model gguf}; PY=${3:-python3}
PIN=00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$(cd "$HERE/../../../engine/src" && pwd -P)
WK=$(mktemp -d "${TMPDIR:-/tmp}/qwen35-metal.XXXXXX")
WK=$(cd "$WK" && pwd -P)  # the real path: the module linker refuses a source path through a symlink (/var)
fail=0
have=$(shasum -a 256 "$MODEL" | cut -c1-64)
[ "$have" = "$PIN" ] || { echo "model digest $have is not the pinned $PIN"; exit 1; }
printf "%s" "$PIN" > "$WK/model.sha256"
WANT="greedy 11751 13 198 32 13 2912 198 33 13 3439 198 15666 25 198 32 271"
# build name model digest-file steps [file-in-engine from to]: a copy of engine/src with one mutation applied
build() {
  rm -rf "$WK/src-$1"; cp -R "$SRC" "$WK/src-$1"
  if [ -n "${5:-}" ]; then
    FROM=$6 TO=$7 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/src-$1/murakumo/engine/$5"
  fi
  sed -e "s#@PATH@#$2#g;s#@EXPECT@#$3#g;s#@IDS@#760 6511 314 9338 369#g;s#@STEPS@#$4#g;s#@LAYERS@#${LAYERS:-32}#g" "$HERE/qwen35_metal.kotoba.in" > "$WK/$1.kotoba"
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --source-path "$WK/src-$1" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
}
runit() { { "$WK/$1" > /dev/null 2> "$WK/$1.txt"; rc=$?; } 2>/dev/null || true; }
mutant() { # name label file from to
  build "$1" "$MODEL" "$WK/model.sha256" 16 "$3" "$4" "$5"
  rc=0; runit "$1"
  got=$(grep '^greedy' "$WK/$1.txt" || true)
  if [ -n "$got" ] && [ "$got" != "$WANT" ]; then echo "$1 ($2): $got (detected)"
  else echo "$1 NOT detected (exit $rc): $got"; fail=1; fi
}

build ok "$MODEL" "$WK/model.sha256" 16
rc=0; runit ok
got=$(grep '^greedy' "$WK/ok.txt" || true)
if [ "$rc" = 0 ] && [ "$got" = "$WANT" ]; then echo "exact: 16 tokens equal llama.cpp's ($(grep '^q35-ns' "$WK/ok.txt"))"
else echo "exact: FAILED (exit $rc): $got $(head -3 "$WK/ok.txt")"; fail=1; fi

mutant tile-mutant "value heads by hv / 2" kernels/deltanet.kotoba "hk = hv % 16;" "hk = hv / 2;"
mutant conv-mutant "conv1d without its state" kernels/deltanet.kotoba "float o = w[0] * s[0] + w[1] * s[1] + w[2] * s[2] + w[3] * x;" "float o = w[3] * x;"
mutant gate-mutant "attention output gate dropped" kernels/attn.kotoba "(o / z) * (1.0f / (1.0f + precise::exp(-gt)))" "(o / z)"
mutant q6k-mutant "Q6_K scale index" kernels/quant.kotoba "[li / 16 + 2 * k]);" "[1 - li / 16 + 2 * k]);"
mutant q4k-mutant "Q4_K/Q5_K sub-block pair scales swapped" kernels/quant.kotoba "dd.x * (s0 * lo + s1 * hi)" "dd.x * (s1 * lo + s0 * hi)"

# residual after 4 layers (3 delta-net layers and the first gated-attention one), last prompt position, first 6 values,
# against ref_forward35.py --layers 4 (numpy, recorded 2026-11-15): within 2e-4 of each
REF4="3.276281 -0.06008644 -0.02928025 -0.1430705 -0.510084 -0.02590235"
resid() { # name: prints the max |difference| of the dump against REF4
  perl -e 'my @r = split / /, $ARGV[0]; my $m = 99; while (<STDIN>) { if (/^dump-x (.*) h/) { my @x = map { unpack("f", pack("L", $_)) } split / /, $1; $m = 0; for my $i (0..5) { my $d = abs($x[$i] - $r[$i]); $m = $d if $d > $m } } } printf "%.3g\n", $m' "$REF4" < "$WK/$1.txt"
}
LAYERS=4; build l4 "$MODEL" "$WK/model.sha256" 1
rc=0; runit l4
d4=$(resid l4)
if [ "$rc" = 0 ] && perl -e "exit(!($d4 <= 2e-4))"; then echo "layer4: residual within $d4 of the oracle"
else echo "layer4: FAILED (exit $rc): max difference $d4"; fail=1; fi
build rm "$MODEL" "$WK/model.sha256" 1 kernels/attn.kotoba "-float(2 * ln) / 64.0f" "-float(2 * ln) / 32.0f"
rc=0; runit rm
dm=$(resid rm)
if perl -e "exit(!($dm > 2e-4 && $dm < 99))"; then echo "rope-mutant (RoPE frequencies doubled): residual off by $dm (detected)"
else echo "rope-mutant NOT detected (exit $rc): $dm"; fail=1; fi
LAYERS=32

last=$(printf "%s" "$PIN" | cut -c64); [ "$last" = 0 ] && flip=1 || flip=0
printf "%s%s" "$(printf "%s" "$PIN" | cut -c1-63)" "$flip" > "$WK/bad.sha256"
build bd "$MODEL" "$WK/bad.sha256" 2
rc=0; runit bd
if [ "$rc" != 0 ] && ! grep -q '^greedy' "$WK/bd.txt"; then echo "digest: one character off refuses before any token (exit $rc)"
else echo "digest: NOT refused (exit $rc)"; fail=1; fi

"$PY" "$HERE/../gguf-metal/make_fixture.py" write "$WK/fixture.gguf"
shasum -a 256 "$WK/fixture.gguf" | cut -c1-64 > "$WK/fixture.sha256"
build wr "$WK/fixture.gguf" "$WK/fixture.sha256" 2
rc=0; runit wr
if [ "$rc" != 0 ] && ! grep -q '^greedy' "$WK/wr.txt"; then echo "wrong-model: refused (exit $rc): $(grep refused "$WK/wr.txt" | head -1)"
else echo "wrong-model: NOT refused (exit $rc)"; fail=1; fi

[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
