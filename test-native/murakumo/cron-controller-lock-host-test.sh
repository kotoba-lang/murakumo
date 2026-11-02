#!/bin/sh
# Run only on a disposable Linux host. Uses test executables and private state.
set -eu
controller=${1:?absolute controller path required}
d=$(mktemp -d /tmp/itonami-lock-test.XXXXXXXX)
cleanup() {
  [ -z "${pid:-}" ] || kill -KILL "$pid" 2>/dev/null || true
  [ -z "${child_pid:-}" ] || kill -KILL "$child_pid" 2>/dev/null || true
  rm -rf "$d"
}
trap cleanup EXIT
mkdir -m 700 "$d/state" "$d/work"
cat > "$d/planner" <<'SH'
#!/bin/sh
if [ -f "$TEST_ROOT/block" ]; then
  echo "$$" > "$TEST_ROOT/planner-pid"
  : > "$TEST_ROOT/ready"
  exec sleep 300
fi
if [ -f "$TEST_ROOT/due" ]; then
  printf 'cursor/plan 100 200 %064d a b c\nrequest\n' 0
else
  echo cursor/idle
fi
SH
cat > "$d/resident" <<'SH'
#!/bin/sh
[ "$1" = refresh ] || { echo dispatched >> "$TEST_ROOT/calls"; exit 0; }
echo resident/retention-ready
SH
cat > "$d/archive" <<'SH'
#!/bin/sh
echo 'resident-archive/confirmed checkpoint tree'
SH
cat > "$d/bridge" <<'SH'
#!/bin/sh
case "$1" in complete) echo bridge/not-completed;; prepare) echo bridge/request-ready;; *) exit 2;; esac
SH
cat > "$d/checkpoint" <<'SH'
#!/bin/sh
exit 0
SH
chmod 700 "$d/planner" "$d/resident" "$d/archive" "$d/bridge" "$d/checkpoint"
export TEST_ROOT="$d"
run() { "$controller" "$d/planner" "$d/bridge" "$d/resident" "$d/archive" "$d/checkpoint" "$d/work" "$d/state" repo "$d/config" "$d/definition" pin "$d/cursor" "$d/source"; }
: > "$d/block"
setsid "$controller" "$d/planner" "$d/bridge" "$d/resident" "$d/archive" "$d/checkpoint" "$d/work" "$d/state" repo "$d/config" "$d/definition" pin "$d/cursor" "$d/source" >"$d/first.out" 2>"$d/first.err" &
pid=$!
n=0
while [ ! -f "$d/ready" ]; do n=$((n+1)); [ "$n" -lt 100 ] || { kill -KILL -- "-$pid" 2>/dev/null || true; exit 1; }; sleep .1; done
child_pid=$(cat "$d/planner-pid")
if run >"$d/concurrent.out" 2>"$d/concurrent.err"; then exit 1; fi
grep -q '^cron-resident/locked-or-unknown$' "$d/concurrent.err"
if mkdir "$d/state/lock" 2>/dev/null; then exit 1; fi
kill -KILL "$pid"
wait "$pid" 2>/dev/null || true
pid=''
[ -f "$d/state/lock/flock-v1" ]
rm "$d/block"
if run >"$d/child-held.out" 2>"$d/child-held.err"; then exit 1; fi
grep -q '^cron-resident/locked-or-unknown$' "$d/child-held.err"
kill -KILL "$child_pid"
child_pid=''
n=0
while ! /usr/bin/flock -n "$d/state/.controller-flock" true; do
  n=$((n+1)); [ "$n" -lt 100 ] || exit 1; sleep .1
done
[ "$(run)" = cron-resident/idle ]
[ ! -e "$d/state/lock" ]
mkdir "$d/state/lock"
if run >"$d/legacy.out" 2>"$d/legacy.err"; then exit 1; fi
grep -q '^cron-resident/locked-or-unknown$' "$d/legacy.err"
[ -d "$d/state/lock" ]
rmdir "$d/state/lock"
mkdir "$d/state/lock"
printf '%s\n' 'cron-resident/flock-v1' > "$d/state/lock/flock-v1"
: > "$d/state/lock/unexpected"
if run >"$d/extra.out" 2>"$d/extra.err"; then exit 1; fi
grep -q '^cron-resident/locked-or-unknown$' "$d/extra.err"
[ -f "$d/state/lock/unexpected" ]
rm "$d/state/lock/unexpected"
rmdir "$d/state/lock"
rm "$d/state/.controller-flock"
ln -s "$d/other" "$d/state/.controller-flock"
if run >"$d/link.out" 2>"$d/link.err"; then exit 1; fi
grep -q '^cron-resident/lock-unavailable$' "$d/link.err"
[ ! -e "$d/other" ]
rm "$d/state/.controller-flock"
: > "$d/due"
mkdir -p "$d/state/j/0000000000000000/dispatch"
printf '%064d\n' 0 > "$d/state/j/0000000000000000/job"
if run >"$d/dispatch.out" 2>"$d/dispatch.err"; then exit 1; fi
grep -q '^cron-resident/dispatch-unknown$' "$d/dispatch.err"
[ -d "$d/state/j/0000000000000000/dispatch" ] && [ ! -e "$d/calls" ]
printf '%s\n' 'lock test passed: exclusion, child-held lock, SIGKILL recovery, legacy and symlink refusal, uncertain dispatch preserved'
