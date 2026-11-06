#!/bin/sh
# Time one SDXL-shaped UNet call on this Mac's GPU (stage M, slice 11): a Kotoba program built by `bin/amu compile
# --runtime-profile amu-program-metal` builds the graph of ComfyUI's SDXL UNetModel (model_channels 320, channel_mult (1, 2, 4), two ResBlocks
# per level, transformer depths 0 / 2 / 10, middle 10, 2,816-wide label embedding, 77 x 2048 context, linear projections) with SYNTHETIC
# weights (pseudo-random half values made on the GPU; 2.57 billion of them, about 5.1 GB), encodes the ~2,100 kernels into ONE command
# buffer and times whole-graph runs (best of 6) for latents of 64, 96 and 128 (512, 768 and 1024 px). The output is checked for finiteness and
# size only: the weights are random, so the values mean nothing and this is not a correctness test. Timings are whatever the host gives
# (other processes on the GPU inflate them): diagnostic only. A GPU host with several GB free is required (not a CI step).
#
#   sh run.sh /path/to/amu-checkout [python3 for the finite check]
set -eu
AMU=${1:?amu checkout}; PY=${2:-python3.11}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/unet-sdxl.XXXXXX")
fail=0
for lat in 64 96 128; do
  sed -e "s#@LAT@#$lat#;s#@REPS@#6#" "$HERE/unet_sdxl_metal.kotoba.in" > "$WK/s$lat.kotoba"
  "$AMU/bin/amu" compile "$WK/s$lat.kotoba" --runtime-profile amu-program-metal -o "$WK/s$lat" > /dev/null
  { timeout 300 "$WK/s$lat" > /dev/null 2> "$WK/s$lat.err"; } 2>/dev/null || true
  ns=$(grep -a -o 'best [0-9]* total' "$WK/s$lat.err" | awk '{print $2}'); nd=$(grep -a -o 'dispatches [0-9]*' "$WK/s$lat.err" | awk '{print $2}')
  chk=$("$PY" - "$WK/s$lat.err" "$lat" <<'PYEOF'
import sys, numpy as np
raw = np.fromfile(sys.argv[1], dtype=np.uint8); lat = int(sys.argv[2])
off = raw[:32].tobytes().index(b"\n") + 1            # skip the "dispatches N" line
a = np.frombuffer(raw[off:off + 4 * lat * lat * 4].tobytes(), dtype=np.float32)
ok = a.size == 4 * lat * lat and not np.isnan(a).any() and not np.isinf(a).any() and float(np.abs(a).max()) < 1e3
print("ok" if ok else "BAD", "n=%d mean|x|=%.3f max|x|=%.3f" % (a.size, float(np.abs(a).mean()), float(np.abs(a).max())))
PYEOF
)
  case "$chk" in ok*) ;; *) fail=1;; esac
  echo "latent ${lat}x${lat} ($((lat * 8)) px): $nd kernels, best $(awk -v n="$ns" 'BEGIN{printf "%.2f", n/1e9}') s per UNet call; output $chk"
done
[ "$fail" = 0 ] && echo "finite outputs, build dir $WK" || { echo "BAD OUTPUT, build dir $WK"; exit 1; }
