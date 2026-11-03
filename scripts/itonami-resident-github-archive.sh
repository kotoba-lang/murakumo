#!/bin/sh
# One scheduled reconciliation. Resident and archive inputs are host-provisioned.
set -eu
if [ "$#" -ne 5 ]; then
  echo 'usage: itonami-resident-github-archive.sh RESIDENT_BIN CONFIG CHECKPOINT_BIN WORKROOT OWNER/REPO' >&2
  exit 2
fi
resident=$1; config=$2; checkpoint=$3; workroot=$4; repo=$5
fail() { printf '%s\n' "$1" >&2; exit 1; }
for path in "$resident" "$config" "$checkpoint" "$workroot"; do
  case "$path" in /*) ;; *) fail 'resident-archive/path-refused';; esac
done
workroot=$(realpath -e -- "$workroot") || fail 'resident-archive/workroot-refused'
# The native resident validates the admitted head, trusted profile and replica
# receipts. Treat all other output, including a zero-exit refusal, as failure.
descriptor=$("$resident" archive "$config") || fail 'resident-archive/head-unavailable'
set -f
set -- $descriptor
[ "$#" -eq 11 ] && [ "$1" = resident/archive ] || fail 'resident-archive/head-refused'
root=$2; author=$3; profile=$4; policy=$5; environment=$6; epoch=$7; parent=$8; task=$9
shift 9
store=$1; ack=$2
ackdir=$(realpath -e -- "$(dirname -- "$ack")") || fail 'resident-archive/ack-directory-refused'
case "$ackdir/" in "$workroot"/|"$workroot"/*/) ;; *) fail 'resident-archive/ack-outside-workroot';; esac
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
result=$("$script_dir/itonami-github-checkpoint-archive.sh" "$checkpoint" "$workroot" "$store" "$repo" "$root" "$author" "$profile" "$policy" "$environment" "$epoch" "$parent" "$task") || fail 'resident-archive/storage-unconfirmed'
set -- $result
[ "$#" -eq 4 ] && [ "$1" = archive/stored ] && [ "$2" = "$root" ] || fail 'resident-archive/storage-invalid'
tree=$3
printf '%s\n' "$tree" | LC_ALL=C grep -Eq '^[0-9a-f]{40}$' || fail 'resident-archive/tree-invalid'
case "$4" in acknowledged|unacknowledged) ;; *) fail 'resident-archive/result-invalid';; esac
umask 077
pending=$(mktemp "$ackdir/.archive-ack.XXXXXXXX") || fail 'resident-archive/temp-failed'
trap 'rm -f -- "$pending"' EXIT HUP INT TERM
now=$(date +%s) || fail 'resident-archive/clock-unavailable'
case "$now" in *[!0-9]*|'') fail 'resident-archive/clock-invalid';; esac
until=$((now * 1000 + 900000))
printf '{"checkpoint":"%s","kind":"itonami.github-archive-ack/1","tree":"%s","until":%s}' "$root" "$tree" "$until" > "$pending"
sync -f "$pending" || fail 'resident-archive/ack-sync-failed'
mv -f -- "$pending" "$ack" || fail 'resident-archive/ack-publish-failed'
sync -f "$ackdir" || fail 'resident-archive/ack-directory-sync-failed'
printf 'resident-archive/confirmed %s %s\n' "$root" "$tree"
