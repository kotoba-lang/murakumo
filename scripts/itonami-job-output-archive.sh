#!/bin/sh
# Immutable archive of one independently pinned, verified job result.
# The host must authorize/classify the output and separately fence effects.
set -eu
if [ "$#" -ne 6 ]; then
  echo 'usage: itonami-job-output-archive.sh VERIFIER WORKROOT LOG OWNER/REPO EXPECTED_DID EXPECTED_JOB' >&2
  exit 2
fi
bin=$1; workroot=$2; log=$3; repo=$4; did=$5; job=$6
fail() { printf '%s\n' "$1" >&2; exit 1; }
case "$bin:$workroot:$log" in /*:/*:/*) ;; *) fail 'job-archive/bad-path';; esac
[ -x "$bin" ] && [ -d "$workroot" ] && [ -f "$log" ] && [ ! -L "$log" ] || fail 'job-archive/input-unavailable'
workroot=$(cd -- "$workroot" && pwd -P) || fail 'job-archive/bad-workroot'
printf '%s\n' "$repo" | LC_ALL=C grep -Eq '^[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+$' || fail 'job-archive/bad-repository'
case "$repo" in *..*) fail 'job-archive/bad-repository';; esac
printf '%s\n' "$did" | LC_ALL=C grep -Eq '^did:key:z[1-9A-HJ-NP-Za-km-z]+$' || fail 'job-archive/bad-did'
printf '%s\n' "$job" | LC_ALL=C grep -Eq '^[0-9a-f]{64}$' || fail 'job-archive/bad-job'
metadata=$(gh api "repos/$repo" --jq '[.full_name,.private] | @tsv') || fail 'job-archive/github-unavailable'
[ "$metadata" = "$(printf '%s\ttrue' "$repo")" ] || fail 'job-archive/repository-not-private-or-mismatch'
umask 077
work=$(mktemp -d "$workroot/job-output.XXXXXXXX") || fail 'job-archive/temp-failed'
trap 'rm -rf -- "$work"' EXIT HUP INT TERM
mkdir -m 700 "$work/store" "$work/repo" "$work/readback" "$work/restored" "$work/recheck"
cp -- "$log" "$work/input.log"
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES
unset GIT_COMMON_DIR GIT_NAMESPACE GIT_CONFIG_PARAMETERS GIT_CONFIG_COUNT GIT_TEMPLATE_DIR GIT_DEFAULT_HASH
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0
export KEXE_ALLOW=20,35,37,38 KEXE_CAP_RESOURCES_20=/usr/bin/stat KEXE_CAP_RESOURCES_35="$workroot"
unset KEXE_STRUCTURED_REPORT
"$bin" "$work/input.log" "$did" "$job" "$work/store" > "$work/verdict" || fail 'job-archive/verification-failed'
cid=$(awk '/^job-output\/stored bafkrei[a-z2-7]+$/ && length($2)==59 && NR==1 { print $2; next } { bad=1 } END { if (bad || NR!=1) exit 1 }' "$work/verdict") || fail 'job-archive/verification-refused'
cp -- "$work/store/$cid" "$work/repo/output.bin"
sed -n 's/^receipt //p' "$work/input.log" > "$work/repo/receipt.json"
sed -n 's/^signature //p' "$work/input.log" > "$work/repo/signature.txt"
printf '{"did":"%s","job":"%s","kind":"itonami.github-job-output/1","output":"%s"}\n' "$did" "$job" "$cid" > "$work/repo/manifest.json"
git -C "$work/repo" init -q --template=
git -C "$work/repo" -c core.hooksPath=/dev/null add -- output.bin receipt.json signature.txt manifest.json
git -C "$work/repo" -c core.hooksPath=/dev/null -c user.name='Itonami archive' -c user.email=archive@example.invalid -c commit.gpgSign=false commit -q -m 'Archive verified Itonami job output'
tree=$(git -C "$work/repo" rev-parse 'HEAD^{tree}')
ref="refs/heads/itonami/job-output/$job"
remote="https://github.com/$repo.git"
if git -C "$work/repo" -c credential.helper= -c 'credential.helper=!gh auth git-credential' -c core.hooksPath=/dev/null push -q "$remote" "HEAD:$ref"; then push_result=acknowledged; else push_result=unacknowledged; fi
git -C "$work/readback" init -q --bare --template=
git -C "$work/readback" -c credential.helper= -c 'credential.helper=!gh auth git-credential' fetch -q --no-tags "$remote" "$ref" || fail 'job-archive/unknown-readback-unavailable'
observed=$(git -C "$work/readback" rev-parse 'FETCH_HEAD^{tree}') || fail 'job-archive/unknown-readback-invalid'
[ "$observed" = "$tree" ] || fail 'job-archive/conflict-or-unknown'
git -C "$work/readback" fsck --strict --full >/dev/null 2>&1 || fail 'job-archive/readback-corrupt'
git --git-dir="$work/readback" --work-tree="$work/restored" -c core.hooksPath=/dev/null checkout -q FETCH_HEAD -- output.bin receipt.json signature.txt manifest.json || fail 'job-archive/readback-checkout-failed'
for name in output.bin receipt.json signature.txt manifest.json; do cmp -s "$work/repo/$name" "$work/restored/$name" || fail 'job-archive/readback-mismatch'; done
"$bin" "$work/input.log" "$did" "$job" "$work/recheck" > "$work/rechecked" || fail 'job-archive/recheck-failed'
[ "$(cat "$work/rechecked")" = "job-output/stored $cid" ] && cmp -s "$work/recheck/$cid" "$work/restored/output.bin" || fail 'job-archive/recheck-mismatch'
commit=$(git -C "$work/readback" rev-parse FETCH_HEAD)
printf 'job-archive/stored %s %s %s %s\n' "$job" "$cid" "$commit" "$push_result"
