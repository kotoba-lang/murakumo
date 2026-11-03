#!/bin/sh
# Run only with a task-owned test resident fixture and its live retention
# receipts; this temporarily replaces its ack and restores it on exit.
set -eu
if [ "$#" -ne 6 ]; then
  echo 'usage: resident-github-barrier-gate.sh RESIDENT_BIN CONFIG CHECKPOINT_BIN WORKROOT OWNER/REPO ACK' >&2
  exit 2
fi
resident=$1; config=$2; checkpoint=$3; workroot=$4; repo=$5; ack=$6
script=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)/scripts/itonami-resident-github-archive.sh
work=$(mktemp -d "$workroot/resident-barrier-gate.XXXXXXXX")
trap 'cp "$work/original" "$ack"; rm -rf -- "$work"' EXIT HUP INT TERM
cp "$ack" "$work/original"
[ "$("$resident" once "$config")" = resident/idle ]
sed -E 's/"until":[0-9]+/"until":1/' "$work/original" > "$ack"
cmp -s "$work/original" "$ack" && exit 1
[ "$("$resident" once "$config")" = resident/archive-pending ]
cp "$ack" "$work/expired"
mkdir "$work/bin"
printf '#!/bin/sh\nexit 1\n' > "$work/bin/gh"
chmod 700 "$work/bin/gh"
if PATH="$work/bin:/usr/bin:/bin" "$script" "$resident" "$config" "$checkpoint" "$workroot" "$repo" > /dev/null 2>&1; then exit 1; fi
cmp -s "$work/expired" "$ack"
cp "$work/original" "$ack"
[ "$("$resident" once "$config")" = resident/idle ]
printf '%s\n' 'resident GitHub barrier integration gate passed'
