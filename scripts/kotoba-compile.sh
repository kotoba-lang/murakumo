#!/bin/sh
# Public portable compile for the operator entries.
# Requires the Release kotoba CLI on PATH. There is no kotoba -M.
# This script does not print an emit envelope (ADAPTER-EMIT is HOLD).
# Release kotoba compile --json reports kotoba.cli/code "emitted" only after
# the output file exists. We check the file on disk, not a wrapper.
set -eu

if ! command -v kotoba >/dev/null 2>&1; then
  echo "kotoba CLI is not on PATH. Could not run:" >&2
  echo "  kotoba compile <entry.kotoba> --target wasm --output <file> --json" >&2
  echo "  kotoba compile <entry.kotoba> --target web --output <file> --json" >&2
  echo "Language pin: kotoba-lang@48d7d3cb (see kotoba-lang.pin.edn)." >&2
  exit 127
fi

out="${KOTOBA_OUT_DIR:-target/kotoba}"
mkdir -p "$out"

entries="kotoba/desired.kotoba kotoba/factory.kotoba kotoba/quic_driver.kotoba kotoba/quic_cert.kotoba"

for entry in $entries; do
  name=$(basename "$entry" .kotoba)
  wasm="$out/$name.wasm"
  web="$out/$name.mjs"
  kotoba compile "$entry" --target wasm --output "$wasm" --json
  if [ ! -f "$wasm" ]; then
    echo "compile did not leave $wasm on disk" >&2
    exit 1
  fi
  kotoba compile "$entry" --target web --output "$web" --json
  if [ ! -f "$web" ]; then
    echo "compile did not leave $web on disk" >&2
    exit 1
  fi
  echo "emitted $wasm ($(wc -c < "$wasm") bytes)"
  echo "emitted $web ($(wc -c < "$web") bytes)"
done
