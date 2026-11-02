#!/bin/sh
# One pinned cron/resident cycle. Private host config and single-writer
# ownership are prerequisites; a lost dispatch marker needs reconciliation.
set -eu
if [ "$#" -ne 13 ]; then
  echo 'usage: itonami-cron-resident-once.sh PLANNER BRIDGE RESIDENT ARCHIVER CHECKPOINT WORKROOT STATE REPO CONFIG DEFINITION PIN CURSOR SOURCE' >&2
  exit 2
fi
planner=$1; bridge=$2; resident=$3; archiver=$4; checkpoint=$5; workroot=$6; state=$7
shift 7
repo=$1; config=$2; definition=$3; pin=$4; cursor=$5; source=$6
fail() { printf '%s\n' "$1" >&2; exit 1; }
for p in "$planner" "$bridge" "$resident" "$archiver" "$checkpoint"; do case "$p" in /*) ;; *) fail 'cron-resident/bad-executable';; esac; [ -x "$p" ] || fail 'cron-resident/bad-executable'; done
for p in "$workroot" "$state" "$config" "$definition" "$cursor" "$source"; do case "$p" in /*) ;; *) fail 'cron-resident/bad-path';; esac; done
[ -d "$workroot" ] && [ -d "$state" ] && [ ! -L "$state" ] || fail 'cron-resident/state-unavailable'
[ "$(/usr/bin/stat -c %a -- "$state")" = 700 ] || fail 'cron-resident/state-not-private'
umask 077
mkdir "$state/lock" 2>/dev/null || fail 'cron-resident/locked-or-unknown'
trap 'rmdir "$state/lock" 2>/dev/null || true' EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
plan=$("$planner" plan "$definition" "$pin" "$cursor" clock) || fail 'cron-resident/plan-failed'
if [ "$plan" = cursor/idle ]; then printf '%s\n' 'cron-resident/idle'; exit 0; fi
header=$(printf '%s\n' "$plan" | sed -n '1p')
[ "$(printf '%s\n' "$plan" | wc -l | tr -d ' ')" = 2 ] || fail 'cron-resident/plan-refused'
set -f
set -- $header
[ "$#" -eq 7 ] && [ "$1" = cursor/plan ] || fail 'cron-resident/plan-refused'
previous=$2; slot=$3; job=$4
for n in "$previous" "$slot"; do printf '%s\n' "$n" | LC_ALL=C grep -Eq '^[0-9]+$' || fail 'cron-resident/plan-invalid'; done
printf '%s\n' "$job" | LC_ALL=C grep -Eq '^[0-9a-f]{64}$' || fail 'cron-resident/plan-invalid'
# Renew an intact but expiring retention pair without invoking inference.
# A corrupt or unverifiable head remains refused; never create a dispatch
# marker until retention and the archive have both been confirmed.
refresh=$("$resident" refresh "$config") || fail 'cron-resident/retention-pending'
case "$refresh" in resident/retention-ready|resident/replicas-refreshed) ;; *) fail 'cron-resident/retention-pending';; esac
# Refresh the archive of the current admitted head before checking completion
# or installing a request. This is an explicit pinned host operation.
archive=$("$archiver" "$resident" "$config" "$checkpoint" "$workroot" "$repo") || fail 'cron-resident/archive-pending'
case "$archive" in 'resident-archive/confirmed '*) ;; *) fail 'cron-resident/archive-pending';; esac
printf '%s\n' "$archive" > "$state/archive-current.log"
check=$("$bridge" complete "$config" "$definition" "$pin" "$cursor" "$source") || fail 'cron-resident/bridge-refused'
if [ "$check" != bridge/completed ]; then
  [ "$check" = bridge/not-completed ] || fail 'cron-resident/bridge-refused'
  prepared=$("$bridge" prepare "$config" "$definition" "$pin" "$cursor" "$source") || fail 'cron-resident/request-refused'
  [ "$prepared" = bridge/request-ready ] || fail 'cron-resident/request-refused'
  [ -d "$state/j" ] || mkdir -m 700 "$state/j" || fail 'cron-resident/state-unavailable'
  short=$(printf '%.16s' "$job"); dir="$state/j/$short"
  if mkdir -m 700 "$dir" 2>/dev/null; then printf '%s\n' "$job" > "$dir/job"; fi
  [ -f "$dir/job" ] && [ "$(cat "$dir/job")" = "$job" ] || fail 'cron-resident/identity-unknown'
  mkdir "$dir/dispatch" 2>/dev/null || fail 'cron-resident/dispatch-unknown'
  /usr/bin/sync -f "$dir/dispatch" && /usr/bin/sync -f "$dir" || fail 'cron-resident/dispatch-unknown'
  "$resident" once "$config" > "$dir/resident.log" 2> "$dir/resident.err" || true
  # No blind retry: an unconfirmed resident result leaves dispatch in place.
  archive=$("$archiver" "$resident" "$config" "$checkpoint" "$workroot" "$repo") || fail 'cron-resident/archive-pending'
  case "$archive" in 'resident-archive/confirmed '*) ;; *) fail 'cron-resident/archive-pending';; esac
  printf '%s\n' "$archive" > "$state/archive-new.log"
  check=$("$bridge" complete "$config" "$definition" "$pin" "$cursor" "$source") || fail 'cron-resident/completion-unknown'
  [ "$check" = bridge/completed ] || fail 'cron-resident/completion-unknown'
fi
advanced=$("$planner" advance "$definition" "$pin" "$cursor" "$previous" "$slot") || fail 'cron-resident/cursor-unknown'
[ "$advanced" = cursor/advanced ] || fail 'cron-resident/cursor-unknown'
printf 'cron-resident/completed %s %s\n' "$slot" "$job"
