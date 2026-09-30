#!/bin/sh
# Run `yataverse bootstrap` on exactly the nodes the OWNER has approved, then consume the approval.
#
# The approval is a file the owner controls: ~/.murakumo-web-provision/approvals.json (mode 0600, in a
# 0700 directory), e.g. {"bootstrap_yataverse": ["benjamin", "gad"], "approved": "owner, 2026-09-30"}.
# This is what makes `bootstrap_yataverse` an OWNER-APPROVAL capability for the web-plane bot: without
# the file (or for a host not listed) nothing runs. Each approval is single-use.
#
# Bootstrapping a node signs it in to Authn, which CREATES AN ACCOUNT. The person or bot that runs this
# is doing that on the owner's approval.
#   scripts/yataverse-run-approved.sh [--dry-run]
set -eu
dir="$HOME/.murakumo-web-provision"; file="$dir/approvals.json"
here=$(cd "$(dirname "$0")" && pwd)
dry=0; [ "${1:-}" = "--dry-run" ] && dry=1
[ -f "$file" ] || { echo "no approvals file ($file): nothing approved, nothing run"; exit 3; }
# `stat` differs between BSD and GNU (and `stat -f` on GNU prints filesystem info), so ask node.
mode() { node -e 'console.log((require("fs").statSync(process.argv[1]).mode & 0o777).toString(8))' "$1"; }
[ "$(mode "$dir")" = "700" ] && [ "$(mode "$file")" = "600" ] || { echo "refusing: $dir must be 0700 and $file 0600"; exit 4; }
hosts=$(node -e '
  const j = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
  const h = j.bootstrap_yataverse;
  if (!Array.isArray(h) || !h.every((x) => typeof x === "string" && /^[A-Za-z0-9._@:-]+$/.test(x) && !x.startsWith("-"))) process.exit(2);
  console.log(h.join(" "));' "$file") || { echo "refusing: malformed approvals file"; exit 4; }
[ -n "$hosts" ] || { echo "no host approved"; exit 3; }
status=0
for h in $hosts; do
  if [ $dry -eq 1 ]; then echo "would bootstrap: $h"; continue; fi
  echo "== bootstrap $h (approved)"
  if "$here/yataverse-node-bootstrap.sh" "$h"; then
    # consume this host's approval only after it succeeded
    node -e '
      const fs = require("fs"); const f = process.argv[1], h = process.argv[2];
      const j = JSON.parse(fs.readFileSync(f, "utf8"));
      j.bootstrap_yataverse = j.bootstrap_yataverse.filter((x) => x !== h);
      fs.writeFileSync(f, JSON.stringify(j) + "\n", { mode: 0o600 });' "$file" "$h"
  else
    echo "!! bootstrap failed on $h; its approval is kept"; status=1
  fi
done
exit $status
