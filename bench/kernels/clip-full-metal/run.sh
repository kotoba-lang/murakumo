#!/bin/sh
# Gate the full-size CLIP text towers of SDXL on the GPU (I3b, fourth slice): one Kotoba program (clip_full_metal.kotoba.in, built by
# `bin/amu compile --runtime-profile amu-program-metal`) reads a Hugging Face CLIPTextModelWithProjection safetensors file (Linear weights
# [out, in]; files over 2 GiB are read in chunks), embeds the 77 token ids, runs NL pre-LN layers (causal 64-wide-head attention, MLP with
# quick_gelu or gelu), and writes (1) the last hidden state after the final LayerNorm, (2) the PENULTIMATE hidden state (output of layer
# NL-1, before the final LayerNorm: what SDXL conditions its UNet on) and (3) the end-token embedding after final LayerNorm and
# text_projection (the pooled vector), all in one command buffer. make_fixture.py builds the same model with transformers' own PyTorch
# classes (RANDOM weights, float32, CPU: a local oracle only) at three sizes: tiny (3 layers, 128), L (OpenAI ViT-L/14 text tower: 12
# layers, 768, 12 heads, 3072, quick_gelu, 123 M parameters), G (OpenCLIP bigG: 32 layers, 1280, 20 heads, 5120, gelu, 695 M parameters,
# a 2.8 GB file). Limits, set before the L and G runs: 2e-2 for tiny and L, 5e-2 for G (|gpu - ref| / max(1, |ref|); f16 weights and
# activations, deeper stacks accumulate more). Mutants (tiny only) must exceed 5e-2 on some output: causal mask off, penultimate taken
# one layer late, activation dropped (a quick_gelu <-> gelu swap moves the tiny model by only 4.3e-2: above the 2e-2 gate limit, below this 5e-2 bar), projection read from the wrong buffer. ms is the best of 5 whole-tower runs: diagnostic.
# A GPU host is required; G needs about 7 GB of RAM for the fixture and 3 GB free disk (not a CI step).
#
#   sh run.sh /path/to/amu-checkout /path/to/comfy/sd1_tokenizer [python with torch, transformers, safetensors, numpy] [configs: "tiny L G"]
set -eu
AMU=${1:?amu checkout}; TOKDIR=${2:?tokenizer dir (vocab.json, merges.txt)}; PY=${3:-python3.11}; CONFIGS=${4:-tiny L G}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/clip-full.XXXXXX")
fail=0
dims() { case "$1" in tiny) NL=3; HID=128; HEADS=2; INTER=512; ACT=1; LIM=0.02;; L) NL=12; HID=768; HEADS=12; INTER=3072; ACT=1; LIM=0.02;;
  G) NL=32; HID=1280; HEADS=20; INTER=5120; ACT=2; LIM=0.05;; esac; }
build() { # name config [from to]
  dims "$2"
  sed -e "s#@MODEL@#$WK/$2/model.safetensors#;s#@IDS@#$WK/$2/ids.txt#;s#@REPS@#5#;s#@NL@#$NL#g;s#@HID@#$HID#g;s#@HEADS@#$HEADS#g;s#@INTER@#$INTER#g;s#@ACT@#$ACT#g;s#@A@#$((128 * HID))#g;s#@F@#$((128 * INTER))#g;s#@W@#$((HID * HID))#g;s#@W1@#$((HID * INTER))#g;s#@GX@#$((HID / 64))#g;s#@GXF@#$((INTER / 64))#g" "$HERE/clip_full_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${3:-}" ]; then
    FROM=$3 TO=$4 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
  { "$WK/$1" > /dev/null 2> "$WK/$1.err"; } 2>/dev/null || true
}
cmp_() { # name config
  dims "$2"; eos=$(python3 -c "ids=list(map(int,open('$WK/$2/ids.txt').read().split()));print(ids.index(max(ids)))")
  "$PY" "$HERE/compare.py" "$WK/$1.err" "$WK/$2" "$HID" "$eos" 2>/dev/null | sed -n 1p
}
for cfg in $CONFIGS; do
  "$PY" "$HERE/make_fixture.py" write "$WK/$cfg" "$TOKDIR" "$cfg" > "$WK/$cfg.log" 2>&1
  dims "$cfg"; build "c$cfg" "$cfg"
  line=$(cmp_ "c$cfg" "$cfg")
  ok=$(echo "$line" | awk -v l="$LIM" '{split($1,a,"=");split($2,b,"=");split($3,c,"=");split($4,d,"="); print (a[2]<=l && b[2]<=l && c[2]<=l && d[2]==1) ? 1 : 0}')
  [ "$ok" = 1 ] || fail=1
  ns=$(grep -a -o 'best [0-9]* total' "$WK/c$cfg.err" | awk '{print $2}')
  echo "$cfg ($(sed -n 's/.*params \([0-9]*\).*/\1/p' "$WK/$cfg.log") parameters): $(echo "$line" | sed 's/  ref.*//') (limit $LIM) $([ "$ok" = 1 ] && echo ok || echo FAIL) best=$(awk -v n="$ns" 'BEGIN{printf "%.1f", n/1e6}') ms"
done
mutant() { # label from to
  build "m$1" tiny "$2" "$3"
  line=$(cmp_ "m$1" tiny); worst=$(echo "$line" | awk '{split($1,a,"=");split($2,b,"=");split($3,c,"="); m=a[2]; if (b[2]>m) m=b[2]; if (c[2]>m) m=c[2]; print m}')
  bad=$(awk -v e="$worst" 'BEGIN{print (e+0 > 0.05 || e != e+0) ? 1 : 0}')
  [ "$bad" = 1 ] && echo "mutant [$1]: worst error $worst (detected)" || { echo "mutant [$1]: worst error $worst NOT detected"; fail=1; }
}
case " $CONFIGS " in *" tiny "*)
mutant causal 'Pat (pbuf-u f R dev 77 2 1 ' 'Pat (pbuf-u f R dev 77 2 0 '
mutant penultimate '(= l (- 3 2))' '(= l (- 3 1))'
mutant activation-dropped 'Pl1 (pbuf-u f R dev 128 128 512 1 0 1 ' 'Pl1 (pbuf-u f R dev 128 128 512 1 0 0 '
mutant projection '(vector-i64 lnh wp zb dummy projf dummy Ppr)' '(vector-i64 qh wp zb dummy projf dummy Ppr)';; esac
# the fixtures are large (up to 2.8 GB): on success the weight files are removed from the build dir unless KEEP=1
if [ "$fail" = 0 ]; then
  [ "${KEEP:-0}" = 1 ] || rm -f "$WK"/*/model.safetensors
  echo "gate passed, build dir $WK"
else echo "GATE FAILED, build dir $WK (weights kept)"; exit 1; fi
