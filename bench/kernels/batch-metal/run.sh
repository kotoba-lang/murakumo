#!/bin/sh
# Measure what one dispatch costs when many share a command buffer (stage M, slice 6) on this Mac's GPU,
# from a Kotoba program built by `bin/amu compile --runtime-profile amu-program-metal`:
#   mode 0  every dispatch in its own command buffer (commit + waitUntilCompleted)
#   mode 1  one command buffer, one compute encoder holding all of them
#   mode 2  one command buffer, one encoder per dispatch
# of a trivial kernel (out = a + b) at two sizes. Reports ns per dispatch and the ratio mode0/mode1, and
# checks only that batching helps (mode1 per dispatch < mode0 per dispatch / 5). The Kotoba host's own
# encoding cost is included. Diagnostic, on whatever load the host has. A GPU host is required (not a CI
# step). Needs awk.
#
#   sh run.sh /path/to/amu-checkout
set -eu
AMU=${1:?amu checkout}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/batch-metal.XXXXXX")
fail=0
for cfg in "400 4096" "400 1048576"; do
  set -- $cfg; D=$1; NT=$2
  sed -e "s/@D@/$D/g;s/@NTHREADS@/$NT/g;s/@REPS@/10/g" "$HERE/batch_metal.kotoba.in" > "$WK/b_$NT.kotoba"
  "$AMU/bin/amu" compile "$WK/b_$NT.kotoba" --runtime-profile amu-program-metal -o "$WK/b_$NT" >/dev/null
  line=$("$WK/b_$NT" 2>&1 >/dev/null | grep batch-ns)
  echo "$line" | awk -v nt="$NT" '{d=$3; printf "threads=%d dispatches=%d  mode0 %.1f us  mode1 %.2f us  mode2 %.2f us per dispatch  mode0/mode1 = %.0fx\n", nt, d, $5/d/1000, $7/d/1000, $9/d/1000, $5/$7}'
  ok=$(echo "$line" | awk '{d=$3; print ($7/d < ($5/d)/5) ? 1 : 0}'); [ "$ok" = 1 ] || fail=1
done
[ "$fail" = 0 ] && echo "gate passed (batching helps), build dir $WK" || { echo "GATE FAILED, build dir $WK"; exit 1; }
