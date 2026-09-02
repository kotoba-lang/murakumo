#!/bin/sh
# Operator guest-run after kotoba compile --target web.
# There is no kotoba -M.
#
# Measured on Release kotoba CLI v0.7.3:
#   kotoba run <entry>.kotoba  → kotoba/runtime-rejected (typed forms)
#   kotoba run <artifact>      → planned / adapter-required (not a result)
# Guest execution is instantiateKotoba on the emitted .mjs (kototama-js-host).
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

need=0
for name in desired factory quic_driver quic_cert; do
  if [ ! -s "target/kotoba/${name}.mjs" ]; then
    echo "missing target/kotoba/${name}.mjs" >&2
    need=1
  fi
done
if [ "$need" -ne 0 ]; then
  echo "compile first: sh scripts/kotoba-compile.sh" >&2
  if ! command -v kotoba >/dev/null 2>&1; then
    echo "kotoba CLI is not on PATH. Could not run:" >&2
    echo "  kotoba compile kotoba/desired.kotoba --target web --output target/kotoba/desired.mjs --json" >&2
  fi
  exit 127
fi

exec node scripts/kotoba-guest-run.mjs
