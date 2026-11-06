#!/bin/sh
# Gate the SDXL UNet GRAPH on this Mac's GPU (stage M, slice 17): unet_sdxl_named_metal.kotoba.in, built by
# `bin/amu compile --runtime-profile amu-program-metal`, reads a safetensors file in ComfyUI's names and runs the whole UNetModel of SDXL's
# topology (time embedding + label embedding of the 2,816-wide vector, 9 input blocks with ResBlocks / Downsamples / SpatialTransformers of
# depth 2 and 10, the middle block of depth 10, 9 output blocks with skip concatenation and Upsamples, the output head, context of 77 tokens
# x 2,048) in ONE command buffer of ~2,100 dispatches. make_fixture.py builds the same model with ComfyUI v0.44.0's own PyTorch UNetModel
# and SDXL's config (supported_models.SDXL) but model_channels 64 instead of 320 (widths 64 / 128 / 256, 158 M parameters, 1,680 tensors,
# RANDOM weights, float32, CPU; a local oracle only), records its output and the activations after every block. The topology, depths,
# context width, head size 64 and label embedding are SDXL's; only the three channel widths are 5x smaller (the kernels' behaviour at
# widths 320 / 640 / 1280 is covered by the timing program in ../unet-sdxl-metal, whose output values are not checked).
# Limits (the f16 scale of slice 10, set before any run of this program): final output 1e-2, every stage 2e-2 (|gpu - ref| / max(1, |ref|)).
# Mutants must push the final error above 5e-2: a skip tensor stored at the wrong offset (the bug this slice found in the timing program),
# the middle block one transformer layer short, the label embedding left out, the specialised 3x3 convolution's validity mask broken (proves that kernel is the one running), cross-attention keys taken from the value weights.
# MUTANTS=0 skips the mutants (they use the first config's size; at MC 128 two of them are below the 5e-2 bar). ms is the best of 3 whole-graph runs on whatever load the host has: diagnostic. A GPU host is required (not a CI step).
#
#   sh run.sh /path/to/amu-checkout /path/to/ComfyUI-0.44.0 /path/to/stubs /path/to/python-with-torch [configs: "64:32 64:64"  (MC:LAT)]
set -eu
AMU=${1:?amu checkout}; COMFY=${2:?ComfyUI v0.44.0 source dir}; STUBS=${3:?stubs dir}; PYC=${4:?python with torch, safetensors, einops, numpy}; CONFIGS=${5:-64:32 64:64}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/unet-sdxl-named.XXXXXX")
fail=0
build() { # name MC LAT [from to]
  sed -e "s#@MODEL@#$WK/mc$2_$3/model.safetensors#;s#@X@#$WK/mc$2_$3/x.f32#;s#@CTX@#$WK/mc$2_$3/ctx.f32#;s#@Y@#$WK/mc$2_$3/y.f32#;s#@REPS@#3#;s#@MC@#$2#g;s#@LAT@#$3#g;s#@TE@#$((4 * $2))#g" "$HERE/unet_sdxl_named_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${4:-}" ]; then
    FROM=$4 TO=$5 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
  { "$WK/$1" > /dev/null 2> "$WK/$1.err"; } 2>/dev/null || true
}
cmp_() { PYTHONPATH="$STUBS" "$PYC" "$HERE/compare.py" "$WK/$1.err" "$WK/mc$2_$3" "$2" "$3" 2>/dev/null | sed -n 1p; }
for cfg in $CONFIGS; do
  mc=${cfg%%:*}; lat=${cfg##*:}
  PYTHONPATH="$STUBS" "$PYC" "$HERE/make_fixture.py" write "$WK/mc${mc}_$lat" "$COMFY" "$mc" "$lat" > "$WK/mc${mc}_$lat.log" 2>&1
  build "c${mc}_$lat" "$mc" "$lat"
  line=$(cmp_ "c${mc}_$lat" "$mc" "$lat")
  ok=$(echo "$line" | awk '{ok=1; for(i=1;i<=NF;i++){split($i,a,"="); if(a[1]=="final"){if(a[2]>0.01) ok=0} else if(a[1]=="finite"){if(a[2]!=1) ok=0} else if(a[2]>0.02) ok=0}; print ok}')
  [ "$ok" = 1 ] || fail=1
  ns=$(grep -a -o 'best [0-9]* total' "$WK/c${mc}_$lat.err" | awk '{print $2}')
  nd=$(grep -a -o 'dispatches [0-9]*' "$WK/c${mc}_$lat.err" | awk '{print $2}')
  echo "MC=$mc latent ${lat}x$lat ($((lat * 8)) px, $(sed -n 's/^params \([0-9]*\).*/\1/p' "$WK/mc${mc}_$lat.log") parameters, $nd dispatches): $line $([ "$ok" = 1 ] && echo ok || echo FAIL) best=$(awk -v n="$ns" 'BEGIN{printf "%.1f", n/1e6}') ms"
done
if [ "${MUTANTS:-1}" = 1 ]; then
# the mutants run on a "strong" fixture (output projections of attention and feed-forward x3): with the plain one a wrong layer count or
# wrong cross-attention keys moves the final output by only ~2e-2, below the 5e-2 bar fixed beforehand. The same build is also checked clean.
cfg=${CONFIGS%% *}; mc=${cfg%%:*}; lat=${cfg##*:}; SD="mc${mc}_${lat}_strong"
PYTHONPATH="$STUBS" "$PYC" "$HERE/make_fixture.py" write "$WK/$SD" "$COMFY" "$mc" "$lat" strong > "$WK/$SD.log" 2>&1
sed -e "s#@MODEL@#$WK/$SD/model.safetensors#;s#@X@#$WK/$SD/x.f32#;s#@CTX@#$WK/$SD/ctx.f32#;s#@Y@#$WK/$SD/y.f32#;s#@REPS@#1#;s#@MC@#$mc#g;s#@LAT@#$lat#g;s#@TE@#$((4 * mc))#g" "$HERE/unet_sdxl_named_metal.kotoba.in" > "$WK/strongclean.kotoba"
"$AMU/bin/amu" compile "$WK/strongclean.kotoba" --runtime-profile amu-program-metal -o "$WK/strongclean" >/dev/null; { "$WK/strongclean" > /dev/null 2> "$WK/strongclean.err"; } 2>/dev/null || true
sline=$(PYTHONPATH="$STUBS" "$PYC" "$HERE/compare.py" "$WK/strongclean.err" "$WK/$SD" "$mc" "$lat" 2>/dev/null | sed -n 1p)
echo "strong fixture, clean build (informational, deep stages grow with the larger activations): $sline"
mutant() { # label from to
  sed -e "s#@MODEL@#$WK/$SD/model.safetensors#;s#@X@#$WK/$SD/x.f32#;s#@CTX@#$WK/$SD/ctx.f32#;s#@Y@#$WK/$SD/y.f32#;s#@REPS@#1#;s#@MC@#$mc#g;s#@LAT@#$lat#g;s#@TE@#$((4 * mc))#g" "$HERE/unet_sdxl_named_metal.kotoba.in" > "$WK/m$1.kotoba"
  FROM=$2 TO=$3 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/m$1.kotoba"
  "$AMU/bin/amu" compile "$WK/m$1.kotoba" --runtime-profile amu-program-metal -o "$WK/m$1" >/dev/null; { "$WK/m$1" > /dev/null 2> "$WK/m$1.err"; } 2>/dev/null || true
  line=$(PYTHONPATH="$STUBS" "$PYC" "$HERE/compare.py" "$WK/m$1.err" "$WK/$SD" "$mc" "$lat" 2>/dev/null | sed -n 1p); worst=$(echo "$line" | sed 's/.*final=\([^ ]*\).*/\1/')
  bad=$(awk -v e="$worst" 'BEGIN{print (e+0 > 0.05 || e != e+0) ? 1 : 0}')
  [ "$bad" = 1 ] && echo "mutant [$1]: final error $worst (detected)" || { echo "mutant [$1]: final error $worst NOT detected"; fail=1; }
}
mutant skip-offset 'cat6 (* (* C1 hw0) 4)' 'cat6 (* (* C0 hw0) 4)'
mutant middle-depth '"middle_block.1." ma 0 mb 0 C2 l2 l2 10 ctxh' '"middle_block.1." ma 0 mb 0 C2 l2 l2 9 ctxh'
mutant label-embedding '(vector-i64 te le emb)' '(vector-i64 te te emb)'
mutant conv-k3s1-padding 'vmask |= (1u << tap);' 'vmask |= 0u;'
mutant cross-keys 'ctxh wk2 z 0 k-h 128 2048 C' 'ctxh wv2 z 0 k-h 128 2048 C'
fi
# the fixtures are large (0.6 GB at MC 64, 4 GB at MC 192): on success the weight files are removed from the build dir unless KEEP=1
if [ "$fail" = 0 ]; then
  [ "${KEEP:-0}" = 1 ] || rm -f "$WK"/mc*/model.safetensors
  echo "gate passed, build dir $WK"
else echo "GATE FAILED, build dir $WK (weights kept)"; exit 1; fi
