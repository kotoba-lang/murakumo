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
#   even-prompt      the prompt and the first exact token (6 tokens) continue with the next 15 exact tokens
#   (the prompt goes through the model up to 16 tokens per pass: simdgroup-matrix matvecs (mm) and one Gated DeltaNet
#   dispatch over the pass's tokens (dnfn); exact and even-prompt take that path)
#   mm-mutant        the multi-token matvec multiplying rows 8 .. 15 of each tile by rows 0 .. 7's weights changes the
#                    tokens (the 5-token prompt takes the 8-column kernel, the chat prompt's passes the 16-column one)
#   dnfn-mutant      the multi-token Gated DeltaNet's conv1d reading only the current token changes the tokens
#   multi-slot-mutant  decode reading the state slot as if the 5-token prompt pass had advanced it five slots
#                    (instead of one) changes the tokens
#   chat-prompt      a 34-token chat prompt (passes of 16, 16 and 2) gives the same 32 tokens as the same prompt two
#                    tokens per pass
#   digest           a pinned digest that differs in one character refuses before any token
#   wrong-model      the synthetic S1 fixture (architecture qwen2) is refused by the qwen35 profile
# With the MTP head file (4th argument: mtp.safetensors, the MTP sidecar of Qwen/Qwen3.5-4B @ 851bf6e as Kapitsa 0.2.36
# pulls it, sha256 below), the
# drafted decode (qwen35_spec.kotoba.in) is checked against the plain one over 48 tokens:
#   spec-exact       the 48 tokens equal the plain decode's (drafts are verified, so they never change the output)
#   accept-mutant    every draft accepted without the verify check changes the tokens
#   slot-mutant      a rejected draft advancing the recurrent state two slots instead of one changes the tokens
#   mtp-mutant       the MTP head's hidden half of the fc input left stale: the tokens stay equal and fewer drafts
#                    are accepted (the drafts only choose what is verified)
#   mtp-digest       an MTP digest that differs in one character refuses before any token
# With the DFlash drafter file (5th argument: model.safetensors of z-lab/Qwen3.5-4B-DFlash @ 9a1996c, sha256 below),
# the DFlash-drafted decode (qwen35_dflash.kotoba.in, blocks of 8) is checked over 48 tokens of a chat prompt that
# opens a think block (the chat-prompt row's ids without the empty think block):
#   dflash-exact     the 48 tokens equal the plain decode's
#   dflash-accept-mutant  every draft accepted without the verify check changes the tokens
#   dflash-replay-mutant  the delta-net states advanced over the whole block instead of the accepted tokens changes
#                    the tokens
#   dflash-ctx-mutant  the drafter's context not updated with the accepted tokens: the tokens stay equal and fewer
#                    than half as many drafts are accepted
#   dflash-digest    a DFlash digest that differs in one character refuses before any token
# Timing on stderr (`q35-ns ...`) is a diagnostic, not a benchmark. A Mac GPU host is required (not a CI step).
#
#   sh run.sh /path/to/amu-checkout /path/to/Qwen3.5-4B-Q4_K_M.gguf [python3] [/path/to/mtp.safetensors]
#             [/path/to/dflash/model.safetensors]
set -eu
AMU=${1:?amu checkout}; MODEL=${2:?model gguf}; PY=${3:-python3}; MTP=${4:-}; DFLASH=${5:-}
DPIN=1eb221d36abb13a5f1b972f8d031a9723fad8cbb7d275abe548b60e77577eb42
MPIN=4f491dd27975520ec9c9bfa970ca215cc7f12a0328761d3656ab7b9f388e472a
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
  sed -e "s#@PATH@#$2#g;s#@EXPECT@#$3#g;s#@IDS@#${IDS:-760 6511 314 9338 369}#g;s#@STEPS@#$4#g;s#@LAYERS@#${LAYERS:-32}#g;s#@MTP@#${DRAFT:-$MTP}#g;s#@MEXPECT@#${MEXP:-}#g;s#@BLOCK@#${BS:-16}#g;s#@MODE@#${MODE:-2}#g" "$HERE/${IN:-qwen35_metal.kotoba.in}" > "$WK/$1.kotoba"
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

# (an assignment before a function call stays set in sh, so each one is unset after its row)
IDS="760 6511 314 9338 369 11751" build ev "$MODEL" "$WK/model.sha256" 15; unset IDS
rc=0; runit ev
got=$(grep '^greedy' "$WK/ev.txt" || true)
if [ "$rc" = 0 ] && [ "$got" = "greedy ${WANT#greedy 11751 }" ]; then echo "even-prompt: the 15 tokens after the exact first one match"
else echo "even-prompt: FAILED (exit $rc): $got"; fail=1; fi

mutant tile-mutant "value heads by hv / 2" kernels/deltanet.kotoba "hk = hv % 16;" "hk = hv / 2;"
mutant conv-mutant "conv1d without its state" kernels/deltanet.kotoba "float o = w[0] * s[0] + w[1] * s[1] + w[2] * s[2] + w[3] * x;" "float o = w[3] * x;"
mutant gate-mutant "attention output gate dropped" kernels/attn.kotoba "(o / z) * (1.0f / (1.0f + precise::exp(-gt)))" "(o / z)"
mutant q6k-mutant "Q6_K scale index" kernels/quant.kotoba "[li / 16 + 2 * k]);" "[1 - li / 16 + 2 * k]);"
mutant q4k-mutant "Q4_K/Q5_K sub-block pair scales swapped" kernels/quant.kotoba "dd.x * (s0 * lo + s1 * hi)" "dd.x * (s1 * lo + s0 * hi)"
mutant mm-mutant "multi-token matvec tile rows" kernels/quant.kotoba "simdgroup_multiply_accumulate(c[1][b], a1, bb, c[1][b]);" "simdgroup_multiply_accumulate(c[1][b], a0, bb, c[1][b]);"
mutant dnfn-mutant "multi-token conv1d without its state" kernels/deltanet.kotoba "float o = w[0] * s0 + w[1] * s1 + w[2] * s2 + w[3] * x;" "float o = w[3] * x;"
mutant multi-slot-mutant "state slot after a multi-token pass" model/qwen35.kotoba "(rem (+ sl (if (> n 2) 1 n)) 3)" "(rem (+ sl n) 3)"

# a chat prompt in passes of 16, 16 and 2 against the same prompt two tokens per pass
CHAT="248045 846 198 814 20139 1204 264 33828 25710 46966 1345 424 369 6992 13 9357 264 2342 2716 41228 364 264 21520 30501 13 248046 198 248045 74455 198 248068 271 248069 271"
IDS="$CHAT" build c16 "$MODEL" "$WK/model.sha256" 32; unset IDS
rc=0; runit c16
IDS="$CHAT" build c2 "$MODEL" "$WK/model.sha256" 32 model/qwen35.kotoba "(let [n (if (>= (- np i) (nb)) (nb) (- np i))" "(let [n (if (>= (- np i) 2) 2 (- np i))"; unset IDS
runit c2
g16=$(grep '^greedy' "$WK/c16.txt" || true); g2=$(grep '^greedy' "$WK/c2.txt" || true)
if [ "$rc" = 0 ] && [ -n "$g16" ] && [ "$g16" = "$g2" ]; then echo "chat-prompt: 32 tokens equal with passes of 16 and of 2 ($(grep '^q35-ns' "$WK/c16.txt" | cut -d' ' -f2-5) against $(grep '^q35-ns' "$WK/c2.txt" | cut -d' ' -f4-5))"
else echo "chat-prompt: FAILED (exit $rc): $g16 / $g2"; fail=1; fi

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

if [ -n "$MTP" ]; then
  mh=$(shasum -a 256 "$MTP" | cut -c1-64)
  [ "$mh" = "$MPIN" ] || { echo "MTP digest $mh is not the pinned $MPIN"; exit 1; }
  printf "%s" "$MPIN" > "$WK/mtp.sha256"
  build p48 "$MODEL" "$WK/model.sha256" 48
  rc=0; runit p48
  W48=$(grep '^greedy' "$WK/p48.txt" || true)
  spec() { # name [file from to]
    MEXP="$WK/mtp.sha256" IN=qwen35_spec.kotoba.in build "$1" "$MODEL" "$WK/model.sha256" 48 "${2:-}" "${3:-}" "${4:-}"; unset MEXP IN
    rc=0; runit "$1"
    sgot=$(grep '^greedy' "$WK/$1.txt" || true); sacc=$(awk '/^q35-spec/{print $5 "/" $3}' "$WK/$1.txt")
  }
  spec s48
  if [ "$rc" = 0 ] && [ -n "$W48" ] && [ "$sgot" = "$W48" ]; then echo "spec-exact: 48 tokens equal the plain decode's, $sacc drafts accepted ($(grep '^q35-ns' "$WK/s48.txt"))"
  else echo "spec-exact: FAILED (exit $rc): $sgot"; fail=1; fi
  a48=${sacc%/*}
  spec am model/qwen35.kotoba "(if (= a0 dr)" "(if (= 1 1)"
  if [ -n "$sgot" ] && [ "$sgot" != "$W48" ]; then echo "accept-mutant (verify check dropped): tokens change (detected)"
  else echo "accept-mutant NOT detected (exit $rc): $sgot"; fail=1; fi
  spec sm model/qwen35.kotoba "q -1 (rem (+ slot 1) 3)" "q -1 (rem (+ slot 2) 3)"
  if [ -n "$sgot" ] && [ "$sgot" != "$W48" ]; then echo "slot-mutant (rejected draft advances two state slots): tokens change (detected)"
  else echo "slot-mutant NOT detected (exit $rc): $sgot"; fail=1; fi
  spec mm model/qwen35.kotoba "(+ mb (+ (oQ) 2560)) eps n)" "(+ mb (oX)) eps n)"
  if [ "$rc" = 0 ] && [ "$sgot" = "$W48" ] && [ "${sacc%/*}" -lt "$a48" ]; then echo "mtp-mutant (fc hidden half stale): tokens equal, $sacc drafts accepted against $a48 (detected)"
  else echo "mtp-mutant NOT detected (exit $rc): $sacc $sgot"; fail=1; fi
  printf "%s%s" "$(printf "%s" "$MPIN" | cut -c1-63)" "$( [ "$(printf "%s" "$MPIN" | cut -c64)" = 0 ] && echo 1 || echo 0)" > "$WK/mbad.sha256"
  MEXP="$WK/mbad.sha256" IN=qwen35_spec.kotoba.in build md "$MODEL" "$WK/model.sha256" 2; unset MEXP IN
  rc=0; runit md
  if [ "$rc" != 0 ] && ! grep -q '^greedy' "$WK/md.txt"; then echo "mtp-digest: one character off refuses before any token (exit $rc)"
  else echo "mtp-digest: NOT refused (exit $rc)"; fail=1; fi
fi

if [ -n "$DFLASH" ]; then
  dh=$(shasum -a 256 "$DFLASH" | cut -c1-64)
  [ "$dh" = "$DPIN" ] || { echo "DFlash digest $dh is not the pinned $DPIN"; exit 1; }
  printf "%s" "$DPIN" > "$WK/dflash.sha256"
  THINK="248045 846 198 814 20139 1204 264 33828 25710 46966 1345 424 369 6992 13 9357 264 2342 2716 41228 364 264 21520 30501 13 248046 198 248045 74455 198"
  IDS="$THINK" build t48 "$MODEL" "$WK/model.sha256" 48; unset IDS
  rc=0; runit t48
  T48=$(grep '^greedy' "$WK/t48.txt" || true)
  dfl() { # name [file from to]
    IDS="$THINK" BS=8 DRAFT="$DFLASH" MEXP="$WK/dflash.sha256" IN=qwen35_dflash.kotoba.in build "$1" "$MODEL" "$WK/model.sha256" 48 "${2:-}" "${3:-}" "${4:-}"; unset IDS BS DRAFT MEXP IN
    rc=0; runit "$1"
    sgot=$(grep '^greedy' "$WK/$1.txt" || true); sacc=$(awk '/^q35-spec/{print $5 "/" $3}' "$WK/$1.txt")
  }
  dfl d48
  if [ "$rc" = 0 ] && [ -n "$T48" ] && [ "$sgot" = "$T48" ]; then echo "dflash-exact: 48 tokens equal the plain decode's, $sacc drafts accepted per cycles ($(grep '^q35-ns' "$WK/d48.txt"))"
  else echo "dflash-exact: FAILED (exit $rc): $sgot / $T48"; fail=1; fi
  d48=${sacc%/*}
  dfl dam model/qwen35.kotoba "(if (= (m/arena-u32 f X (+ (oTOK) (+ 17 j))) (m/arena-u32 f X (+ (oTOK) j)))" "(if (= 1 1)"
  if [ -n "$sgot" ] && [ "$sgot" != "$T48" ]; then echo "dflash-accept-mutant (verify check dropped): tokens change (detected)"
  else echo "dflash-accept-mutant NOT detected (exit $rc): $sgot"; fail=1; fi
  dfl drm model/qwen35.kotoba "(replay! f R en X PL P (+ acc 1)" "(replay! f R en X PL P bs"
  if [ -n "$sgot" ] && [ "$sgot" != "$T48" ]; then echo "dflash-replay-mutant (state advanced over the whole block): tokens change (detected)"
  else echo "dflash-replay-mutant NOT detected (exit $rc): $sgot"; fail=1; fi
  dfl dxm model/qwen35.kotoba "(dctx! f R en X PL D (+ acc 1) (- pos (+ acc 1)))" "0"
  if [ "$rc" = 0 ] && [ "$sgot" = "$T48" ] && [ $((2 * ${sacc%/*})) -lt "$d48" ]; then echo "dflash-ctx-mutant (context not updated): tokens equal, $sacc accepted against $d48 (detected)"
  else echo "dflash-ctx-mutant NOT detected (exit $rc): $sacc $sgot"; fail=1; fi
  printf "%s%s" "$(printf "%s" "$DPIN" | cut -c1-63)" "$( [ "$(printf "%s" "$DPIN" | cut -c64)" = 0 ] && echo 1 || echo 0)" > "$WK/dbad.sha256"
  DRAFT="$DFLASH" MEXP="$WK/dbad.sha256" IN=qwen35_dflash.kotoba.in build dd "$MODEL" "$WK/model.sha256" 2; unset DRAFT MEXP IN
  rc=0; runit dd
  if [ "$rc" != 0 ] && ! grep -q '^greedy' "$WK/dd.txt"; then echo "dflash-digest: one character off refuses before any token (exit $rc)"
  else echo "dflash-digest: NOT refused (exit $rc)"; fail=1; fi
fi

[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
