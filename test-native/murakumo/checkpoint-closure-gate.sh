#!/bin/sh
# Native integration gate, using an independently prepared signed fixture.
# Arguments: BIN STORE ROOT AUTHOR PROFILE POLICY ENV EPOCH PARENT TASK EXPECTED_CIDS CORRUPT_CID
set -eu
if [ "$#" -ne 12 ]; then
  echo 'usage: checkpoint-closure-gate.sh BIN STORE ROOT AUTHOR PROFILE POLICY ENV EPOCH PARENT TASK EXPECTED_CIDS CORRUPT_CID' >&2
  exit 2
fi
bin=$1; source_store=$2; root_cid=$3; author=$4; profile=$5; policy=$6
shift 6
environment=$1; epoch=$2; parent=$3; task=$4; expected=$5; corrupt=$6
case "$bin" in /*) ;; *) echo 'BIN must be absolute' >&2; exit 2;; esac
case "$corrupt" in *[!a-z2-7]*|'') echo 'invalid corrupt CID' >&2; exit 2;; esac
work=$(mktemp -d "${TMPDIR:-/tmp}/checkpoint-closure-gate.XXXXXXXX")
trap 'rm -rf -- "$work"' EXIT HUP INT TERM
mkdir "$work/store"
cp -R "$source_store"/. "$work/store/"
unrelated=bafkreiccgfeev3gegektq66ni6c43zkxuqvqih3abhgadvyuidcvzpsiyq
test ! -e "$work/store/$unrelated"
printf 'unrelated test data\n' > "$work/store/$unrelated"
export KEXE_ALLOW=20,35,37,38 KEXE_CAP_RESOURCES_20=/usr/bin/stat KEXE_CAP_RESOURCES_35="$work"
run() { "$bin" closure "$work/store" "$root_cid" "$author" "$1" "$policy" "$environment" "$epoch" "$parent" "$task"; }
run "$profile" > "$work/actual.out"
test "$(tail -n 1 "$work/actual.out")" = checkpoint/closure-complete
sed -n 's/^checkpoint\/block //p' "$work/actual.out" | LC_ALL=C sort -u > "$work/actual"
LC_ALL=C sort -u "$expected" > "$work/expected"
diff -u "$work/expected" "$work/actual"
run sha256:0000000000000000000000000000000000000000000000000000000000000000 > "$work/wrong.out"
test "$(cat "$work/wrong.out")" = checkpoint/profile
printf x >> "$work/store/$corrupt"
run "$profile" > "$work/corrupt.out"
test "$(cat "$work/corrupt.out")" = checkpoint/missing-or-corrupt-block
printf '%s\n' 'checkpoint closure integration gate passed'
