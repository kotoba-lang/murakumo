#!/bin/sh
# Copy the yataverse CLI (zero-dependency Node) to a node: ~/.local/share/yataverse.
# Touches nothing else. It creates no account and needs no credential.
#   scripts/yataverse-deploy.sh <ssh-host> [path-to-yataverse-checkout]
set -eu
host=${1:?usage: yataverse-deploy.sh <ssh-host> [checkout]}
src=${2:-"$(cd "$(dirname "$0")/../../yataverse" && pwd)"}
case "$host" in -*|*[!A-Za-z0-9._@:-]*) echo "refusing unsafe host" >&2; exit 2;; esac
[ -f "$src/bin/yataverse.mjs" ] || { echo "no yataverse checkout at $src" >&2; exit 2; }
COPYFILE_DISABLE=1 tar -C "$src" -cf - bin src package.json LICENSE | ssh -o BatchMode=yes -o ConnectTimeout=8 -- "$host" \
  'set -e; d="$HOME/.local/share/yataverse"; rm -rf "$d.new"; mkdir -p "$d.new"; tar -C "$d.new" -xf -; rm -rf "$d.old"; [ -d "$d" ] && mv "$d" "$d.old"; mv "$d.new" "$d"; node "$d/bin/yataverse.mjs" cid /dev/null >/dev/null && echo "installed $d"'
