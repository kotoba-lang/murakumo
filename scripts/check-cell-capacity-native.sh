#!/bin/sh
# Run on Linux aarch64 with a pinned Amu root and pinned tsukasa sources.
set -eu
: "${AMU_ROOT:?set AMU_ROOT to the pinned static root compiler}"
: "${TSUKASA_SOURCE:?set TSUKASA_SOURCE to the pinned tsukasa checkout}"
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
gate=$(mktemp -d)
trap 'rm -rf "$gate"' EXIT HUP INT TERM
{
  printf '%s\n' '(ns capacity-gate (:export [main]))'
  sed '1,/test-runtime-classes]))/d' "$TSUKASA_SOURCE/kotoba/tsukasa/ledger_core.kotoba"
  sed '1,/test-target test-steps test-legal test-paths]))/d' "$TSUKASA_SOURCE/kotoba/tsukasa/node_state.kotoba"
  sed '1,/test-fresh test-labels test-eligible test-placement test-scenarios]))/d' "$repo/kotoba/fleet_placement_core.kotoba"
  sed '1,/test-cell-eligibility test-cell-state-path test-cell-capacity]))/d' "$repo/kotoba/fleet_cell_placement.kotoba"
  printf '%s\n' '(defn main [] :i64 (if (and (test-fresh) (test-cell-eligibility) (test-cell-state-path) (test-cell-capacity)) 0 1))'
} > "$gate/gate.kotoba"
sed 's/(> demand (- (- (- capacity reserve) committed) unmanaged))/false/' "$gate/gate.kotoba" > "$gate/mutant.kotoba"
for name in gate mutant; do
  KEXE_CAP_RESOURCES_35="$gate" "$AMU_ROOT" compile "$gate/$name.kotoba" --target aarch64-linux --output "$gate/$name.elf" --format static
  chmod +x "$gate/$name.elf"
  report=$(KEXE_STRUCTURED_REPORT=1 "$gate/$name.elf")
  printf '%s: %s\n' "$name" "$report"
  case "$name:$report" in
    'gate:'*':result 0 '*) ;;
    'mutant:'*':result 1 '*) ;;
    *) exit 1 ;;
  esac
done
