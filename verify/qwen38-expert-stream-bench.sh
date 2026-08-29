#!/usr/bin/env bash
set -euo pipefail

# Lossless Qwen3.8-Flash-Next comparison cell for a 16 GiB M4 fleet node.
# MODEL_DIR must contain all three UD-IQ3_XXS shards. Pass the first shard;
# BigMoe resolves the remaining split files itself.
BMOE=${BMOE:-"$HOME/src/BigMoeOnEdge/build/cli/bmoe-cli"}
MODEL_DIR=${MODEL_DIR:-"$HOME/models/Qwen3.8-Flash-Next-UD-IQ3_XXS"}
MODEL=${MODEL:-"$MODEL_DIR/Qwen3.8-Flash-Next-UD-IQ3_XXS-00001-of-00003.gguf"}
OUT=${OUT:-"$HOME/qwen38-expert-stream-evidence"}
PROMPT=${PROMPT:-"Explain why the sky is blue in one sentence."}
TOKENS=${TOKENS:-64}

mkdir -p "$OUT"

expected=(10946624 49567921344 32382955968)
for i in 1 2 3; do
  shard=$(printf "%s/Qwen3.8-Flash-Next-UD-IQ3_XXS-%05d-of-00003.gguf" "$MODEL_DIR" "$i")
  actual=$(stat -f %z "$shard")
  if [[ "$actual" != "${expected[$((i - 1))]}" ]]; then
    echo "shard $i size mismatch: expected ${expected[$((i - 1))]}, got $actual" >&2
    exit 2
  fi
done

common=(-m "$MODEL" -p "$PROMPT" -n "$TOKENS" --seed 42 --temp 0
        -t 4 -c 2048 --ubatch 512 --chatml --no-think --progress)

run_stream() {
  local name=$1 cache=$2
  "$BMOE" "${common[@]}" --moe-stream --cache-mb "$cache" --io-threads 4 \
    --dense-weights mmap --prefetch 0 --csv "$OUT/$name.csv" \
    >"$OUT/$name.log" 2>&1
}

# Cache-off is the streaming control: same selected expert bytes and math, no
# retained expert. Two cache-on runs prove determinism and expose warm reuse.
run_stream stream-cache0 0
run_stream stream-cache2000-a 2000
run_stream stream-cache2000-b 2000

# The ordinary mmap baseline can fault through the entire 81.96 GB model on a
# 16 GiB machine. It is opt-in so a routine parity run cannot hang the node.
if [[ "${RUN_MMAP_BASELINE:-0}" == "1" ]]; then
  "$BMOE" "${common[@]}" --csv "$OUT/mmap-baseline.csv" \
    >"$OUT/mmap-baseline.log" 2>&1
fi

# The pinned engine is patched with verify/bmoe-token-id-telemetry.patch so
# parity is established on actual vocabulary rows, not merely rendered text.
token_ids() {
  grep '^BMOE_PROGRESS ' "$1" | sed 's/^BMOE_PROGRESS //' |
    jq -er '.token_id' | paste -sd, -
}

ids0=$(token_ids "$OUT/stream-cache0.log")
idsa=$(token_ids "$OUT/stream-cache2000-a.log")
idsb=$(token_ids "$OUT/stream-cache2000-b.log")
if [[ "$ids0" != "$idsa" || "$idsa" != "$idsb" ]]; then
  echo "actual emitted token ID parity failed" >&2
  exit 3
fi
if [[ "${RUN_MMAP_BASELINE:-0}" == "1" ]]; then
  idsm=$(token_ids "$OUT/mmap-baseline.log")
  if [[ "$ids0" != "$idsm" ]]; then
    echo "streamed vs mmap token ID parity failed" >&2
    exit 4
  fi
fi
printf '%s\n' "$ids0" >"$OUT/token-ids.txt"

echo "$OUT"
