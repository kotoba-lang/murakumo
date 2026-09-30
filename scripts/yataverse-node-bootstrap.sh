#!/bin/sh
# Run `yataverse bootstrap` ON a node, with that node's own key: it signs in to Authn (which CREATES
# AN ACCOUNT on first use), makes the node's tenant and service account and stores the secret 0600
# in the node's ~/.config/yataverse. Nothing secret leaves the node or is printed.
# Run this yourself, or let the web-plane bot run it AFTER you approve the node.
#   scripts/yataverse-node-bootstrap.sh <ssh-host> [tenant-name] [storage]
set -eu
host=${1:?usage: yataverse-node-bootstrap.sh <ssh-host> [tenant-name] [storage]}
tenant=${2:-murakumo-$host}
storage=${3:-blocks}
case "$host$tenant$storage" in -*|*[!A-Za-z0-9._@:-]*) echo "refusing unsafe argument" >&2; exit 2;; esac
ssh -o BatchMode=yes -o ConnectTimeout=8 -- "$host" \
  "node \"\$HOME/.local/share/yataverse/bin/yataverse.mjs\" bootstrap --yes --tenant-name \"$tenant\" --sa-name \"web-node-$host\" --storage \"$storage\""
