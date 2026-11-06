#!/bin/sh
# Per-kernel GPU time of one SDXL-shaped UNet call (stage M, slice 23). unet_sdxl_profile.kotoba.in is the slice-11 timing program (SYNTHETIC weights, ~2,100
# kernels) with a different last step: every dispatch runs in its own command buffer and the program reads the buffer's GPUStartTime / GPUEndTime
# (through valueForKey: -> NSNumber -> description, parsed to nanoseconds), so a table of microseconds per kernel is the time the GPU spent, not
# wall-clock time that other processes' CPU load can stretch. Two passes are printed per size (the first includes warm-up); fill_h / fill_f / the
# fp32 -> fp16 weight conversions are the program's synthetic-weight setup and are listed apart from the model's own kernels. Other processes' GPU use still
# inflates the figures (a pass-to-pass spread of 5 to 20 percent was seen): compare binaries by alternating runs and taking the minimum.
#
#   sh run.sh /path/to/amu-checkout [latent sizes, default "64 96"]
set -eu
AMU=${1:?amu checkout}; SIZES=${2:-64 96}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/unet-sdxl-profile.XXXXXX")
for lat in $SIZES; do
  sed -e "s#@LAT@#$lat#;s#@REPS@#1#" "$HERE/unet_sdxl_profile.kotoba.in" > "$WK/p$lat.kotoba"
  "$AMU/bin/amu" compile "$WK/p$lat.kotoba" --runtime-profile amu-program-metal -o "$WK/p$lat" > /dev/null
  { timeout 600 "$WK/p$lat" > /dev/null 2> "$WK/p$lat.err"; } 2>/dev/null || true
  echo "latent ${lat}x${lat} ($((lat * 8)) px)"
  strings "$WK/p$lat.err" | awk '
    /^PASS2/ {pass=2; next}
    /^K / { p = (pass == 2) ? 2 : 1; cnt[p, $2] = $3; us[p, $2] = $4; names[$2] = 1 }
    /^T / { if (pass == 2) top = top sprintf("    slowest: dispatch %s (%s) %.1f ms\n", $2, $3, $4 / 1000) }
    END {
      printf "  %-12s %6s %12s %12s\n", "kernel", "count", "pass1 ms", "pass2 ms"
      for (n in names) {
        if (n ~ /^fill_|^cvt/) { setup1 += us[1, n]; setup2 += us[2, n]; continue }
        printf "  %-12s %6d %12.1f %12.1f\n", n, cnt[2, n], us[1, n] / 1000, us[2, n] / 1000 | "sort -k4,4nr"
        t1 += us[1, n]; t2 += us[2, n]
      }
      close("sort -k4,4nr")
      printf "  %-12s %6s %12.1f %12.1f   (model kernels)\n", "TOTAL", "", t1 / 1000, t2 / 1000
      printf "  %-12s %6s %12.1f %12.1f   (synthetic-weight fill and fp32->fp16 conversion)\n", "setup", "", setup1 / 1000, setup2 / 1000
      printf "%s", top
    }'
done
echo "build dir $WK"
