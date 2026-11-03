#!/bin/sh
# One schedule, one host-serialized cycle. All arguments and ITONAMI_* values
# are trusted host config, never model/checkpoint input. No automatic cleanup
# of uncertain attempts; a lost lock or incomplete log needs reconciliation.
set -eu
if [ "$#" -ne 12 ]; then
  echo 'usage: itonami-cron-run-once.sh PLANNER NODE ARCHIVER VERIFIER WORKROOT STATE REPO DID DEFINITION SCHEDULE_CID SOURCE CURSOR' >&2
  exit 2
fi
planner=$1; node=$2; archiver=$3; verifier=$4; workroot=$5; state=$6
shift 6
repo=$1; did=$2; definition=$3; pin=$4; source=$5; cursor=$6
: "${ITONAMI_NODE:?}" "${ITONAMI_KEY:?}" "${ITONAMI_DOMAIN:?}" "${ITONAMI_ADMITTED:?}"
: "${ITONAMI_INDEX:?}" "${ITONAMI_LAYOUT:?}" "${ITONAMI_VOTERS:?}" "${ITONAMI_DIAL_GRANTS:?}"
: "${ITONAMI_BALLOT:?}" "${ITONAMI_TOKEN_FILE:?}" "${ITONAMI_ATTEMPTS:?}"
fail() { printf '%s\n' "$1" >&2; exit 1; }
for p in "$planner" "$node" "$archiver" "$verifier"; do case "$p" in /*) ;; *) fail 'cron-run/bad-executable';; esac; [ -x "$p" ] || fail 'cron-run/bad-executable'; done
for p in "$workroot" "$state" "$definition" "$source" "$cursor"; do case "$p" in /*) ;; *) fail 'cron-run/bad-path';; esac; done
[ -d "$workroot" ] && [ -d "$state" ] && [ ! -L "$state" ] || fail 'cron-run/state-unavailable'
case "$(uname -s)" in
  Darwin) state_mode=$(/usr/bin/stat -f %Lp "$state") ;;
  *) state_mode=$(/usr/bin/stat -c %a -- "$state") ;;
esac
[ "$state_mode" = 700 ] || fail 'cron-run/state-not-private'
umask 077
mkdir "$state/lock" 2>/dev/null || fail 'cron-run/locked-or-unknown'
trap 'rmdir "$state/lock" 2>/dev/null || true' EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
export KEXE_ALLOW=7,20,23,35,37,38,45,46,47 KEXE_CAP_RESOURCES_35="$workroot"
export KEXE_CAP_RESOURCES_20=/usr/bin/podman:/usr/bin/stat:/usr/bin/mkdir
export KEXE_CAP_RESOURCES_45="$ITONAMI_DIAL_GRANTS" KEXE_SPAWN_ENV=HOME:XDG_RUNTIME_DIR:PATH
unset KEXE_STRUCTURED_REPORT
plan=$("$planner" plan "$definition" "$pin" "$cursor" clock) || fail 'cron-run/plan-failed'
if [ "$plan" = cursor/idle ]; then printf '%s\n' 'cron-run/idle'; exit 0; fi
header=$(printf '%s\n' "$plan" | sed -n '1p')
[ "$(printf '%s\n' "$plan" | wc -l | tr -d ' ')" = 2 ] || fail 'cron-run/plan-refused'
set -f
set -- $header
[ "$#" -eq 7 ] && [ "$1" = cursor/plan ] || fail 'cron-run/plan-refused'
previous=$2; slot=$3; job=$4; input=$5; profile=$6; policy=$7
for n in "$previous" "$slot"; do printf '%s\n' "$n" | LC_ALL=C grep -Eq '^[0-9]+$' || fail 'cron-run/plan-invalid'; done
printf '%s\n' "$job" | LC_ALL=C grep -Eq '^[0-9a-f]{64}$' || fail 'cron-run/plan-invalid'
for c in "$input" "$policy"; do printf '%s\n' "$c" | LC_ALL=C grep -Eq '^bafkrei[a-z2-7]{52}$' || fail 'cron-run/plan-invalid'; done
printf '%s\n' "$profile" | LC_ALL=C grep -Eq '^sha256:[0-9a-f]{64}$' || fail 'cron-run/plan-invalid'
short=$(printf '%.16s' "$job")
[ -d "$state/j" ] || mkdir -m 700 "$state/j" || fail 'cron-run/state-unavailable'
dir="$state/j/$short"
if mkdir -m 700 "$dir" 2>/dev/null; then printf '%s\n' "$job" > "$dir/job"; fi
[ -f "$dir/job" ] && [ "$(cat "$dir/job")" = "$job" ] || fail 'cron-run/attempt-identity-unknown'
wrapper=$(printf '%s\n' "$plan" | sed -n '2p')
if [ -e "$dir/input" ]; then [ "$(cat "$dir/input")" = "$wrapper" ] || fail 'cron-run/input-conflict'; else printf '%s' "$wrapper" > "$dir/input"; fi
log="$dir/output.log"
if [ ! -e "$log" ] && (set -C; : > "$log") 2>/dev/null; then
  "$node" run-cron "$profile" "$input" "$policy" murakumo "$ITONAMI_NODE" "$ITONAMI_KEY" \
    "$ITONAMI_DOMAIN" "$ITONAMI_ADMITTED" clock 2 "$ITONAMI_INDEX" "$ITONAMI_LAYOUT" \
    "$ITONAMI_VOTERS" "$ITONAMI_BALLOT" follow "$dir/input" "$ITONAMI_TOKEN_FILE" \
    "$ITONAMI_ATTEMPTS" "$previous" "$pin" "$definition" "$source" capture-output > "$log" 2> "$dir/node.err" || true
fi
# Existing or partial logs are never blindly re-executed. The archive command
# snapshots and validates the signed receipt/output and independently reads
# GitHub back; a partial/unknown outcome therefore blocks cursor advancement.
archive=$("$archiver" "$verifier" "$workroot" "$log" "$repo" "$did" "$job") || fail 'cron-run/archive-or-outcome-unknown'
case "$archive" in "job-archive/stored $job "*) ;; *) fail 'cron-run/archive-refused';; esac
advance=$("$planner" advance "$definition" "$pin" "$cursor" "$previous" "$slot") || fail 'cron-run/cursor-unknown'
[ "$advance" = cursor/advanced ] || fail 'cron-run/cursor-unknown'
printf 'cron-run/completed %s %s\n' "$slot" "$job"
