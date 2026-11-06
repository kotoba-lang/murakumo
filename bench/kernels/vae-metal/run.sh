#!/bin/sh
# Gate ComfyUI's VAE Decoder (SDXL's decoder architecture) on this Mac's GPU (stage M, slice 16): vae_metal.kotoba.in, built by
# `bin/amu compile --runtime-profile amu-program-metal`, reads a safetensors file in ComfyUI's names (post_quant_conv, conv_in, mid.block_1,
# mid.attn_1, mid.block_2, up.3 .. up.0 with three residual blocks and an Upsample each, norm_out, conv_out), and decodes a latent to an image
# with GroupNorm(32, eps 1e-6)+SiLU, 3x3 implicit-GEMM convolutions, the 1x1 shortcut convolution where the width changes, nearest-neighbour
# 2x Upsample + conv, and the middle block's single-head attention over all pixels (head dim = mid width: 128 in the small config, 512 in
# SDXL's), in 125 to 169 dispatches, each command buffer of 2 dispatches waited for and its status checked (a long command buffer or a long dispatch is killed by the GPU watchdog at 1024 px).
# make_fixture.py builds the same Decoder with ComfyUI v0.44.0's own PyTorch classes (random weights, float32, CPU: a local oracle only) at
# ch 32 (small, with the activation after every stage) and ch 128 (SDXL's VAE decoder: 49.5 M parameters) at 512 and 1024 px.
# Limits (the f16 scale fixed in slice 10, before any VAE run): final image 1e-2, each stage 2e-2 (|gpu - ref| / max(1, |ref|)).
# Mutants (small config) must exceed 5e-2: attention scale 1 instead of 1/sqrt(C), Upsample reading the wrong column, no residual in the
# identity ResBlock, SiLU dropped from GroupNorm+SiLU. ms is the best of 3 to 5 whole decodes on whatever load the host has: diagnostic.
# VAESTRESS=K (e.g. 20000) multiplies every residual block's output convolution by K, so the oracle's residual stream exceeds the float16 range (65504); mutants are skipped. Not a CI step. A GPU host with ~7 GB free is needed for 1024 px.
#
#   sh run.sh /path/to/amu-checkout /path/to/ComfyUI-0.44.0 /path/to/stubs /path/to/python-with-torch [configs: "mini sdxl64 sdxl128"]
set -eu
AMU=${1:?amu checkout}; COMFY=${2:?ComfyUI v0.44.0 source dir}; STUBS=${3:?stubs dir}; PYC=${4:?python with torch, safetensors, einops, numpy}; CONFIGS=${5:-mini sdxl64 sdxl128}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/vae-metal.XXXXXX")
fail=0
dims() { case "$1" in mini) CH=32; L=8; TAPS=1;; sdxl64) CH=128; L=64; TAPS=0;; sdxl128) CH=128; L=128; TAPS=0;; esac; }
build() { # name config [from to]
  dims "$2"
  SB=$(python3 -c "import struct;print(struct.unpack('<I',struct.pack('<f',($CH*4)**-0.5))[0])")
  sed -e "s#@MODEL@#$WK/$2/model.safetensors#;s#@Z@#$WK/$2/z.f32#;s#@REPS@#3#;s#@CH@#$CH#g;s#@L@#$L#g;s#@NDC@#$((CH * 4 / 64))#g;s#@SCALEBITS@#$SB#;s#@TAPS@#$TAPS#g" "$HERE/vae_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${3:-}" ]; then
    FROM=$3 TO=$4 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
  { "$WK/$1" > /dev/null 2> "$WK/$1.err"; } 2>/dev/null || true
}
cmp_() { dims "$2"; PYTHONPATH="$STUBS" "$PYC" "$HERE/compare.py" "$WK/$1.err" "$WK/$2" "$CH" "$L" "$TAPS" 2>/dev/null | sed -n 1p; }
for cfg in $CONFIGS; do
  dims "$cfg"
  PYTHONPATH="$STUBS" "$PYC" "$HERE/make_fixture.py" write "$WK/$cfg" "$COMFY" "$CH" "$L" $([ "$TAPS" = 1 ] && echo taps) > "$WK/$cfg.log" 2>&1
  build "c$cfg" "$cfg"
  line=$(cmp_ "c$cfg" "$cfg")
  if [ "${VAESTRESS:-0}" != 0 ]; then   # float16-overflow stress: stages judged relative to their own largest value (5e-3; chosen after the first run, which gave 3e-4 to 2.4e-3), the image as always (1e-2), and, where the activations are tapped (the small config), the oracle must really exceed 65504
    ok=$(echo "$line" | awk '{ok=1; tap=0; for(i=1;i<=NF;i++){split($i,a,"="); if(a[1]=="cin") tap=1}; for(i=1;i<=NF;i++){split($i,a,"="); if(a[1]=="final"){if(a[2]>0.01) ok=0} else if(a[1] ~ /^rel_/){if(a[2]>0.005) ok=0} else if(a[1]=="finite"){if(a[2]!=1) ok=0} else if(a[1]=="refmax"){if(tap && a[2]<65504) ok=0}}; print ok}')
  else
  ok=$(echo "$line" | awk '{ok=1; for(i=1;i<=NF;i++){split($i,a,"="); if(a[1]=="final"&&a[2]>0.01) ok=0; else if(a[1]!="finite"&&a[1]!="ref|final|max"&&a[1]!="final"&&a[1]!="refmax"&&a[1] !~ /^rel_/&&a[2]>0.02) ok=0; if(a[1]=="finite"&&a[2]!=1) ok=0}; print ok}')
  fi
  [ "$ok" = 1 ] || fail=1
  ns=$(grep -a -o 'best [0-9]* total' "$WK/c$cfg.err" | awk '{print $2}')
  echo "$cfg ($(sed -n 's/^params \([0-9]*\).*/\1/p' "$WK/$cfg.log") parameters, $((8 * L)) px): $(echo "$line" | sed 's/  finite.*//') $([ "$ok" = 1 ] && echo ok || echo FAIL) best=$(awk -v n="$ns" 'BEGIN{printf "%.2f", n/1e9}') s"
done
mutant() { # label from to
  [ "${VAESTRESS:-0}" = 0 ] || return 0
  build "m$1" mini "$2" "$3"
  line=$(cmp_ "m$1" mini); worst=$(echo "$line" | awk '{m=0; for(i=1;i<=NF;i++){split($i,a,"="); if(a[1]=="final" && a[2]+0>m) m=a[2]+0}; print m}')
  bad=$(awk -v e="$worst" 'BEGIN{print (e+0 > 0.05 || e != e+0) ? 1 : 0}')
  [ "$bad" = 1 ] && echo "mutant [$1]: final error $worst (detected)" || { echo "mutant [$1]: final error $worst NOT detected"; fail=1; }
}
case " $CONFIGS " in *" mini "*)
mutant attn-scale 'float SCALE = as_type<float>(P[1]);' 'float SCALE = 1.0f;'
mutant upsample 'O[i] = half(X[(c * IH + y / 2u) * IW + x / 2u] * Sc[1]);' 'O[i] = half(X[(c * IH + y / 2u) * IW + (x + 1u) / 2u] * Sc[1]);'
mutant residual '(vector-i64 cout cout ih iw 3 1 2))
      (let [sc (scl!' '(vector-i64 cout cout ih iw 3 1 1))
      (let [sc (scl!'
mutant silu 'O[base + i] = half(v / (1.0f + precise::exp(-v)));' 'O[base + i] = half(v);';; esac
if [ "${VAESTRESS:-0}" != 0 ]; then
  build mnoscale mini 'Sc[0] = s; Sc[1] = 1.0f / s;' 'Sc[0] = 1.0f; Sc[1] = 1.0f;'
  l=$(cmp_ mnoscale mini)
  case "$l" in *finite=0*) echo "mutant [range guard removed]: non-finite output (detected)";; *) echo "mutant [range guard removed]: NOT detected"; fail=1;; esac
fi
# the fixtures are large (up to 2.8 GB): on success the weight files are removed from the build dir unless KEEP=1
if [ "$fail" = 0 ]; then
  [ "${KEEP:-0}" = 1 ] || rm -f "$WK"/*/model.safetensors
  echo "gate passed, build dir $WK"
else echo "GATE FAILED, build dir $WK (weights kept)"; exit 1; fi
