#!/bin/sh
# Install/refresh the web-node bundle on one fleet node (ADR-260929).
# Touches only ~/.murakumo-web/bundle on the target; no service, no listener.
#   scripts/web-deploy.sh <ssh-host> [home-dir-name]
set -eu
host=${1:?usage: web-deploy.sh <ssh-host> [home-dir-name]}
home=${2:-.murakumo-web}
case "$host$home" in *[!A-Za-z0-9._@:/~-]*) echo "refusing unsafe host/home" >&2; exit 2;; esac
case "$host" in -*) echo "refusing host starting with '-'" >&2; exit 2;; esac
case "$home" in -*|/*|*..*) echo "refusing unsafe home dir name" >&2; exit 2;; esac
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
"$here/web-bundle.sh" "$tmp/bundle" >/dev/null
COPYFILE_DISABLE=1 tar -C "$tmp" -cf - bundle | ssh -o BatchMode=yes -o ConnectTimeout=8 -- "$host" \
  "set -e; d=\"\$HOME/$home\"; mkdir -p \"\$d\"; rm -rf \"\$d/bundle.new\"; mkdir \"\$d/bundle.new\"; \
   tar -C \"\$d/bundle.new\" --strip-components=1 -xf -; \
   (cd \"\$d/bundle.new\" && shasum -a 256 -c MANIFEST.sha256 >/dev/null 2>&1 || sha256sum -c MANIFEST.sha256 >/dev/null); \
   rm -rf \"\$d/bundle.old\"; [ -d \"\$d/bundle\" ] && mv \"\$d/bundle\" \"\$d/bundle.old\"; mv \"\$d/bundle.new\" \"\$d/bundle\"; echo installed \"\$d/bundle\""
