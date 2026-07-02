#!/bin/sh
# murakumo infer prewarm — populate the rpc-server tensor cache straight from HF
# usage: fetch.sh <manifest>  (lines: <fnv-hex> <part-no> <offset> <size>)
B="https://huggingface.co/pipenetwork/GLM-5.2-REAP50-Q2_K-GGUF/resolve/main"
D="$HOME/Library/Caches/llama.cpp/rpc"
mkdir -p "$D"
fetch_one() {
  h=$1; p=$2; off=$3; sz=$4
  f="$D/$h"
  if [ -f "$f" ] && [ "$(stat -f %z "$f")" = "$sz" ]; then return 0; fi
  end=$((off + sz - 1))
  for try in 1 2 3 4 5; do
    curl -sL --max-time 900 -r "$off-$end" -o "$f.tmp" "$B/GLM-5.2-REAP50-Q2_K-0000$p-of-00004.gguf" \
      && [ "$(stat -f %z "$f.tmp")" = "$sz" ] && mv "$f.tmp" "$f" && return 0
    sleep $((try*5))
  done
  echo "FAIL $h" >&2; return 1
}
N=0; FAIL=0
while read -r h p off sz; do
  fetch_one "$h" "$p" "$off" "$sz" || FAIL=$((FAIL+1))
  N=$((N+1))
  [ $((N % 5)) -eq 0 ] && echo "progress: $N done"
done < "$1"
echo "done: $N fetched, $FAIL failed"
