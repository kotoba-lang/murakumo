#!/bin/sh
# Gate the sampling loop of an SDXL render on this Mac's GPU (stage M, slice 14): sampler_metal.kotoba.in, built by
# `bin/amu compile --runtime-profile amu-program-metal`, makes the discrete sigma table (scaled-linear betas) on the GPU, picks the
# schedule like ComfyUI's "simple" scheduler, and runs Euler steps with EPS input scaling and classifier-free guidance on the denoised
# values around two toy elementwise "models" (a stand-in, NOT a UNet). compare.py runs the same thing with ComfyUI v0.44.0's own
# ModelSamplingDiscrete, and the source text of its own simple_scheduler, sample_euler, to_d and append_dims (extracted from the
# files by ast: importing comfy.samplers / k_diffusion.sampling drags in the whole model stack), float32 on CPU. Limits, set before
# the first agreeing run: sigma table and schedule 1e-5, final latent 1e-4, all relative to the largest reference value. The final
# latent is the result of ~4000-fold cancellation (14 -> 0.003), so it is a sharp test of every step. Mutants must exceed 1e-2:
# guidance dropped, input scaling dropped, schedule read from the wrong end, Euler step sign flipped.
# Not a UNet, not a sampler other than Euler, not the random initial noise or the max-denoise noise scaling. A GPU host is required.
#
#   sh run.sh /path/to/amu-checkout /path/to/ComfyUI-0.44.0 /path/to/stubs /path/to/python-with-torch
set -eu
AMU=${1:?amu checkout}; COMFY=${2:?ComfyUI v0.44.0 source dir}; STUBS=${3:?stubs dir}; PYC=${4:?python with torch, numpy, tqdm}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/sampler-metal.XXXXXX")
fail=0
N=4096
build() { # name steps [from to]
  sed -e "s/@N@/$N/;s/@STEPS@/$2/" "$HERE/sampler_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${3:-}" ]; then
    FROM=$3 TO=$4 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
  "$WK/$1" > /dev/null 2> "$WK/$1.err" || true
}
cmp_() { PYTHONPATH="$STUBS" "$PYC" "$HERE/compare.py" "$WK/$1.err" "$COMFY" "$N" "$2" 2>/dev/null; }
for steps in 20 30; do
  build "s$steps" "$steps"
  line=$(cmp_ "s$steps" "$steps" | sed -n 1p); echo "steps=$steps: $line"
  ok=$(echo "$line" | awk '{split($1,a,"=");split($2,b,"=");split($3,c,"="); print (a[2]<=1e-5 && b[2]<=1e-5 && c[2]<=1e-4) ? 1 : 0}')
  [ "$ok" = 1 ] || fail=1
done
mutant() { # label from to
  build "m$1" 20 "$2" "$3"
  m=$(cmp_ "m$1" 20 | sed -n 1p | sed 's/.*final=//')
  bad=$(awk -v e="$m" 'BEGIN{print (e+0 > 0.01 || e != e+0) ? 1 : 0}')
  [ "$bad" = 1 ] && echo "mutant [$1]: final error $m (detected)" || { echo "mutant [$1]: final error $m NOT detected"; fail=1; }
}
mutant guidance 'float den = du + (dc - du) * cfg;' 'float den = du;'
mutant inputscale 'XIN[i] = X[i] / precise::sqrt(s * s + 1.0f);' 'XIN[i] = X[i];'
mutant schedule 'SG[i] = ST[999u - idx];' 'SG[i] = ST[idx];'
mutant sign 'X[i] = x + d * (sn - s);' 'X[i] = x + d * (s - sn);'
[ "$fail" = 0 ] && echo "gate passed, build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
