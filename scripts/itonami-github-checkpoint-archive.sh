#!/bin/sh
# Archive a verified, operator-classified checkpoint as immutable objects.
# This does not advance an authoritative bot head or enforce a writer lease.
set -eu
if [ "$#" -ne 12 ]; then
  echo 'usage: itonami-github-checkpoint-archive.sh BIN WORKROOT STORE REPO ROOT AUTHOR PROFILE POLICY ENV EPOCH PARENT TASK' >&2
  exit 2
fi
bin=$1; workroot=$2; store=$3; repo=$4; root=$5; author=$6
shift 6
profile=$1; policy=$2; environment=$3; epoch=$4; parent=$5; task=$6
fail() { printf '%s\n' "$1" >&2; exit 1; }
cid() { printf '%s\n' "$1" | LC_ALL=C grep -Eq '^bafkrei[a-z2-7]{52}$'; }
case "$bin" in /*) ;; *) fail 'archive/bad-binary';; esac
case "$workroot" in /*) ;; *) fail 'archive/bad-workroot';; esac
workroot=$(realpath -e -- "$workroot") || fail 'archive/bad-workroot'
store=$(realpath -e -- "$store") || fail 'archive/bad-store'
case "$store/" in "$workroot"/*/) ;; *) fail 'archive/store-outside-workroot';; esac
printf '%s\n' "$repo" | LC_ALL=C grep -Eq '^[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+$' || fail 'archive/bad-repository'
case "$repo" in *..*) fail 'archive/bad-repository';; esac
cid "$root" && cid "$policy" && cid "$environment" || fail 'archive/bad-cid'
[ "$parent" = root ] || cid "$parent" || fail 'archive/bad-parent'
printf '%s\n' "$author" | LC_ALL=C grep -Eq '^did:key:z[1-9A-HJ-NP-Za-km-z]+$' || fail 'archive/bad-author'
printf '%s\n' "$profile" | LC_ALL=C grep -Eq '^sha256:[0-9a-f]{64}$' || fail 'archive/bad-profile'
printf '%s\n' "$epoch" | LC_ALL=C grep -Eq '^[1-9][0-9]{0,8}$' || fail 'archive/bad-epoch'
printf '%s\n' "$task" | LC_ALL=C grep -Eq '^[A-Za-z0-9_-]{1,120}$' || fail 'archive/bad-task'
# GitHub privacy and the exact owner/repository are checked before writing.
metadata=$(gh api "repos/$repo" --jq '[.full_name,.private] | @tsv') || fail 'archive/github-unavailable'
[ "$metadata" = "$(printf '%s\ttrue' "$repo")" ] || fail 'archive/repository-not-private-or-mismatch'
work=$(mktemp -d "$workroot/github-archive.XXXXXXXX") || fail 'archive/temp-failed'
trap 'rm -rf -- "$work"' EXIT HUP INT TERM
mkdir "$work/objects" "$work/repo"
# Keep Git and its hooks/configuration separate from the source worktree.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES
unset GIT_COMMON_DIR GIT_NAMESPACE GIT_CONFIG_PARAMETERS GIT_CONFIG_COUNT GIT_TEMPLATE_DIR GIT_DEFAULT_HASH
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0
export KEXE_ALLOW=20,35,37,38 KEXE_CAP_RESOURCES_20=/usr/bin/stat KEXE_CAP_RESOURCES_35="$workroot"
"$bin" closure "$store" "$root" "$author" "$profile" "$policy" "$environment" "$epoch" "$parent" "$task" > "$work/closure"
# Never consume a partial list, unexpected diagnostic, malformed CID, or extra
# data after the completion marker. Some native failures still exit zero.
awk '
  /^checkpoint\/block bafkrei[a-z2-7]+$/ && length($2)==59 && !done { print $2; n++; next }
  /^checkpoint\/closure-complete$/ && !done && n>0 { done=1; next }
  { bad=1 }
  END { if (bad || !done) exit 1 }
' "$work/closure" > "$work/cids" || fail 'archive/closure-refused'
LC_ALL=C sort -u "$work/cids" > "$work/unique"
while IFS= read -r object; do
  [ -f "$store/$object" ] && [ ! -L "$store/$object" ] || fail 'archive/object-missing'
  cp -- "$store/$object" "$work/objects/$object" || fail 'archive/copy-failed'
done < "$work/unique"
# Re-verify the copied bytes and signed contract; no unrelated store is copied.
"$bin" verify "$work/objects" "$root" "$author" "$profile" "$policy" "$environment" "$epoch" "$parent" "$task" > "$work/verify"
[ "$(cat "$work/verify")" = checkpoint/verified ] || fail 'archive/copied-closure-refused'
cp -R "$work/objects" "$work/repo/blocks"
printf '{"author":"%s","environment":"%s","epoch":%s,"kind":"itonami.github-checkpoint-archive/1","parent":"%s","policy":"%s","profile":"%s","root":"%s","task":"%s"}\n' "$author" "$environment" "$epoch" "$parent" "$policy" "$profile" "$root" "$task" > "$work/repo/manifest.json"
git -C "$work/repo" init -q --template=
git -C "$work/repo" -c core.hooksPath=/dev/null add -- blocks manifest.json
git -C "$work/repo" -c core.hooksPath=/dev/null -c user.name='Itonami archive' -c user.email=archive@example.invalid -c commit.gpgSign=false commit -q -m 'Archive verified Itonami checkpoint'
tree=$(git -C "$work/repo" rev-parse HEAD^{tree})
ref="refs/heads/itonami/archive/$root"
remote="https://github.com/$repo.git"
# No force push or mutable head: concurrent independent initializations can
# conflict. An ambiguous push is successful only after exact tree readback.
if git -C "$work/repo" -c credential.helper= -c 'credential.helper=!gh auth git-credential' -c core.hooksPath=/dev/null push -q "$remote" "HEAD:$ref"; then
  push_result=acknowledged
else
  push_result=unacknowledged
fi
mkdir "$work/readback"
git -C "$work/readback" init -q --bare --template=
if ! git -C "$work/readback" -c credential.helper= -c 'credential.helper=!gh auth git-credential' fetch -q --no-tags "$remote" "$ref"; then
  fail 'archive/unknown-readback-unavailable'
fi
observed=$(git -C "$work/readback" rev-parse 'FETCH_HEAD^{tree}') || fail 'archive/unknown-readback-invalid'
[ "$observed" = "$tree" ] || fail 'archive/conflict-or-unknown'
git -C "$work/readback" fsck --strict --full >/dev/null 2>&1 || fail 'archive/readback-corrupt'
mkdir "$work/restored"
git --git-dir="$work/readback" --work-tree="$work/restored" -c core.hooksPath=/dev/null checkout -q FETCH_HEAD -- blocks manifest.json || fail 'archive/readback-checkout-failed'
cmp -s "$work/repo/manifest.json" "$work/restored/manifest.json" || fail 'archive/readback-manifest-mismatch'
"$bin" verify "$work/restored/blocks" "$root" "$author" "$profile" "$policy" "$environment" "$epoch" "$parent" "$task" > "$work/readback-verdict"
[ "$(cat "$work/readback-verdict")" = checkpoint/verified ] || fail 'archive/readback-verification-failed'
printf 'archive/stored %s %s %s\n' "$root" "$observed" "$push_result"
