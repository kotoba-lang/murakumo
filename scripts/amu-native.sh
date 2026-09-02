#!/bin/sh
# Sealed native kexe for a whole-component operator entry.
# Exact invocation from kotoba-amu (kotoba-lang/amu README + pin 48d7d3cb
# lang/q9-migration.edn). Not kotoba -M, not clojure -M, not short aarch64.
#
#   bin/amu check <entry.kotoba> --jvm-free
#   bin/amu compile <entry.kotoba> --target aarch64-macos --jvm-free --output <name>.kexe
#   bin/amu verify <name>.kexe
#
# Entries (not the 37 kotoba/*_core.kotoba oracle cores):
#   kotoba/desired.kotoba
#   kotoba/factory.kotoba
#
# Output is a sealed kexe, not an OS binary. Linux kexe-verify is HOLD.
# This script does not fake :ok on Linux.
set -eu

entry="${1:-kotoba/desired.kotoba}"
name=$(basename "$entry" .kotoba)

if [ "$(uname -s)" != "Darwin" ] || [ "$(uname -m)" != "arm64" ]; then
  echo "Linux kexe-verify is HOLD. Native release identity is --target aarch64-macos." >&2
  echo "Would run (on aarch64-macos, with bin/amu from kotoba-lang/amu):" >&2
  echo "  bin/amu check $entry --jvm-free" >&2
  echo "  bin/amu compile $entry --target aarch64-macos --jvm-free --output ${name}.kexe" >&2
  echo "  bin/amu verify ${name}.kexe" >&2
  exit 78
fi

if [ ! -x bin/amu ]; then
  echo "bin/amu is not present. Could not run:" >&2
  echo "  bin/amu check $entry --jvm-free" >&2
  echo "  bin/amu compile $entry --target aarch64-macos --jvm-free --output ${name}.kexe" >&2
  echo "  bin/amu verify ${name}.kexe" >&2
  exit 127
fi

bin/amu check "$entry" --jvm-free
bin/amu compile "$entry" --target aarch64-macos --jvm-free --output "${name}.kexe"
bin/amu verify "${name}.kexe"
