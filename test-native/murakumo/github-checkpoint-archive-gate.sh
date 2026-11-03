#!/bin/sh
# Exercise the real archive script and native binary against a private test
# fixture and an isolated file-backed Git remote; no GitHub writes occur.
set -eu
if [ "$#" -ne 10 ]; then
  echo 'usage: github-checkpoint-archive-gate.sh BIN STORE ROOT AUTHOR PROFILE POLICY ENV EPOCH PARENT TASK' >&2
  exit 2
fi
bin=$1; source_store=$2; root=$3; author=$4; profile=$5; policy=$6
shift 6
environment=$1; epoch=$2; parent=$3; task=$4
script=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)/scripts/itonami-github-checkpoint-archive.sh
base=$(mktemp -d "${TMPDIR:-/tmp}/github-checkpoint-gate.XXXXXXXX")
trap 'rm -rf -- "$base"' EXIT HUP INT TERM
mkdir "$base/bin" "$base/work"
cp -R "$source_store" "$base/work/store"
/usr/bin/git init -q --bare "$base/remote.git"
export TEST_REMOTE="$base/remote.git" TEST_PRIVATE=true
cat > "$base/bin/gh" <<'GH'
#!/bin/sh
printf 'cloud-itonami/itonami-state\t%s\n' "$TEST_PRIVATE"
GH
cat > "$base/bin/git" <<'GIT'
#!/bin/sh
mode=other
for arg do
  [ "$arg" = push ] && mode=push
  [ "$arg" = fetch ] && mode=fetch
done
if [ "$mode" = fetch ] && [ "${TEST_FAIL_FETCH:-0}" = 1 ]; then exit 1; fi
/usr/bin/git -c protocol.file.allow=always -c "url.file://$TEST_REMOTE.insteadOf=https://github.com/cloud-itonami/itonami-state.git" "$@"
result=$?
if [ "$mode" = push ] && [ "${TEST_LOST_ACK:-0}" = 1 ] && [ "$result" = 0 ]; then exit 1; fi
exit "$result"
GIT
chmod 700 "$base/bin/gh" "$base/bin/git"
export PATH="$base/bin:/usr/bin:/bin"
run() { "$script" "$bin" "$base/work" "$base/work/store" cloud-itonami/itonami-state "$root" "$author" "$1" "$policy" "$environment" "$epoch" "$parent" "$task"; }
ref="refs/heads/itonami/archive/$root"
# A lost acknowledgement can be resolved only by verified readback.
TEST_LOST_ACK=1 run "$profile" > "$base/first.out"
grep -q '^archive/stored .* unacknowledged$' "$base/first.out"
first=$(/usr/bin/git -C "$TEST_REMOTE" rev-parse "$ref")
run "$profile" > "$base/retry.out" 2>/dev/null
[ "$first" = "$(/usr/bin/git -C "$TEST_REMOTE" rev-parse "$ref")" ]
if TEST_PRIVATE=false run "$profile" > /dev/null 2>&1; then exit 1; fi
if run sha256:0000000000000000000000000000000000000000000000000000000000000000 > /dev/null 2>&1; then exit 1; fi
if TEST_FAIL_FETCH=1 run "$profile" > /dev/null 2>&1; then exit 1; fi
[ "$first" = "$(/usr/bin/git -C "$TEST_REMOTE" rev-parse "$ref")" ]
# Deliberately alter the root in the private fixture copy; leave source intact.
printf x >> "$base/work/store/$root"
if run "$profile" > /dev/null 2>&1; then exit 1; fi
[ "$first" = "$(/usr/bin/git -C "$TEST_REMOTE" rev-parse "$ref")" ]
# A different tree at the same ref must never be overwritten or accepted.
cp "$source_store/$root" "$base/work/store/$root"
empty=$(/usr/bin/git -C "$TEST_REMOTE" mktree </dev/null)
foreign=$(printf 'other\n' | /usr/bin/git -C "$TEST_REMOTE" -c user.name=Fixture -c user.email=fixture@example.invalid commit-tree "$empty")
/usr/bin/git -C "$TEST_REMOTE" update-ref "$ref" "$foreign"
if run "$profile" > /dev/null 2>&1; then exit 1; fi
[ "$foreign" = "$(/usr/bin/git -C "$TEST_REMOTE" rev-parse "$ref")" ]
printf '%s\n' 'GitHub checkpoint archive integration gate passed'
