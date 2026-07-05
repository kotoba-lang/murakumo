#!/usr/bin/env bash
# hwmetrics-report.sh — one CPU/GPU/mem/disk snapshot of the murakumo fleet,
# POSTed to https://api.murakumo.cloud/infer/hwmetrics for the live dashboard
# at https://murakumo.cloud/infer/hwmetrics/ui.
#
# Runs ON gad (the head): reads gad's own /proc + sysfs directly (no root
# needed — amdgpu exposes gpu_busy_percent/vram counters under an
# unprivileged sysfs path), then SSHes each mesh worker for a real CPU/GPU
# sample via `sudo -n powermetrics` (passwordless sudo already provisioned on
# these Macs) + vm_stat/df/sysctl. WORKERS mirrors fleet.edn's :host list —
# fleet.edn stays the SSoT; update both if the mesh roster changes. A worker
# that's asleep/offline is reported `reachable:false`, not dropped silently.
set -euo pipefail

MURAKUMO_API_URL="${MURAKUMO_API_URL:-https://api.murakumo.cloud/infer/hwmetrics}"
WORKERS=(naphtali simeon judah zebulun levi joseph issachar dan benjamin asher)
SSH_OPTS=(-o ConnectTimeout=4 -o BatchMode=yes -o StrictHostKeyChecking=accept-new)

ts_ms() { echo $(( $(date +%s%N) / 1000000 )); }

# ── gad itself (head, Linux/AMD, no ssh — read locally) ─────────────────────
head_snapshot() {
  local u1 n1 s1 i1 io1 irq1 sirq1 u2 n2 s2 i2 io2 irq2 sirq2
  read -r _ u1 n1 s1 i1 io1 irq1 sirq1 _ < /proc/stat
  sleep 0.2
  read -r _ u2 n2 s2 i2 io2 irq2 sirq2 _ < /proc/stat
  local idle1=$((i1+io1)) idle2=$((i2+io2))
  local total1=$((u1+n1+s1+i1+io1+irq1+sirq1)) total2=$((u2+n2+s2+i2+io2+irq2+sirq2))
  local dt=$((total2-total1)) di=$((idle2-idle1))
  local cpu_pct gpu_pct vram_used vram_total mem_total mem_used disk_total disk_used
  cpu_pct=$(awk -v dt="$dt" -v di="$di" 'BEGIN{ if(dt>0) printf "%.1f", 100*(dt-di)/dt; else print "0" }')
  gpu_pct=$(cat /sys/class/drm/card1/device/gpu_busy_percent 2>/dev/null || echo 0)
  vram_used=$(cat /sys/class/drm/card1/device/mem_info_vram_used 2>/dev/null || echo 0)
  vram_total=$(cat /sys/class/drm/card1/device/mem_info_vram_total 2>/dev/null || echo 0)
  read -r mem_total mem_used <<<"$(free -b | awk '/^Mem:/ {print $2, $3}')"
  read -r disk_total disk_used <<<"$(df -k / | tail -1 | awk '{printf "%d %d", $2*1024, ($2-$4)*1024}')"
  jq -cn --arg name gad --arg role head --arg os linux \
     --argjson reachable true --argjson ts "$(ts_ms)" \
     --argjson cpu_pct "$cpu_pct" --argjson gpu_pct "$gpu_pct" \
     --argjson gpu_vram_used_bytes "$vram_used" --argjson gpu_vram_total_bytes "$vram_total" \
     --argjson mem_used_bytes "$mem_used" --argjson mem_total_bytes "$mem_total" \
     --argjson disk_used_bytes "$disk_used" --argjson disk_total_bytes "$disk_total" \
     '{name:$name, role:$role, os:$os, reachable:$reachable, ts:$ts, cpu_pct:$cpu_pct,
       gpu_pct:$gpu_pct, gpu_vram_used_bytes:$gpu_vram_used_bytes,
       gpu_vram_total_bytes:$gpu_vram_total_bytes,
       mem_used_bytes:$mem_used_bytes, mem_total_bytes:$mem_total_bytes,
       disk_used_bytes:$disk_used_bytes, disk_total_bytes:$disk_total_bytes}'
}

# remote probe run in one ssh round-trip: powermetrics (root, already
# passwordless-sudo'd on the fleet) for real CPU/GPU activity, vm_stat/df/
# sysctl for memory+disk. Markers split the blob for awk on the gad side (no
# jq needed on the Mac).
read -r -d '' REMOTE_PROBE <<'EOF' || true
sudo -n powermetrics -n1 -i1000 --samplers cpu_power,gpu_power 2>/dev/null
echo '===MEM==='
vm_stat
sysctl -n hw.memsize hw.pagesize hw.perflevel0.logicalcpu hw.perflevel1.logicalcpu
echo '===DISK==='
df -k /
EOF

# one mesh worker (Apple Silicon) → json snapshot, or {reachable:false} if
# the ssh round-trip fails (asleep/offline — common; the fleet isn't always
# fully awake, see ADR-2607051431).
worker_snapshot() {
  local host="$1" out
  if ! out=$(ssh "${SSH_OPTS[@]}" "$host" "$REMOTE_PROBE" 2>/dev/null); then
    jq -cn --arg name "$host" --arg role worker --arg os macos \
       --argjson reachable false --argjson ts "$(ts_ms)" \
       '{name:$name, role:$role, os:$os, reachable:$reachable, ts:$ts}'
    return 0
  fi

  local e_res p_res gpu_res gpu_mw e_cores p_cores mem_total pagesize disk_total disk_used
  e_res=$(awk -F'[ %]+' '/^E-Cluster HW active residency:/ {print $5; exit}' <<<"$out")
  p_res=$(awk -F'[ %]+' '/^P-Cluster HW active residency:/ {print $5; exit}' <<<"$out")
  gpu_res=$(awk -F'[ %]+' '/^GPU HW active residency:/ {print $5; exit}' <<<"$out")
  gpu_mw=$(awk -F'[ :]+' '/^GPU Power:/ {print $3; exit}' <<<"$out")
  # the four sysctl -n values print as 4 bare-number lines between ===MEM===
  # (after vm_stat's text) and ===DISK===; order is fixed by the sysctl -n
  # call in REMOTE_PROBE above.
  read -r mem_total pagesize e_cores p_cores <<<"$(sed -n '/===MEM===/,/===DISK===/p' <<<"$out" | grep -E '^[0-9]+$' | tr '\n' ' ')"
  read -r disk_total disk_used <<<"$(sed -n '/===DISK===/,$p' <<<"$out" | tail -1 | awk '{printf "%d %d", $2*1024, ($2-$4)*1024}')"

  local active_pages wired_pages compressed_pages mem_used
  active_pages=$(awk '/Pages active:/ {gsub(/\./,"",$3); print $3}' <<<"$out")
  wired_pages=$(awk '/Pages wired down:/ {gsub(/\./,"",$4); print $4}' <<<"$out")
  compressed_pages=$(awk '/Pages occupied by compressor:/ {gsub(/\./,"",$5); print $5}' <<<"$out")
  mem_used=$(( (${active_pages:-0} + ${wired_pages:-0} + ${compressed_pages:-0}) * ${pagesize:-16384} ))

  local cpu_pct
  cpu_pct=$(awk -v e="${e_res:-0}" -v p="${p_res:-0}" -v ec="${e_cores:-4}" -v pc="${p_cores:-6}" \
    'BEGIN{ tot=ec+pc; if(tot>0) printf "%.1f", (e*ec + p*pc)/tot; else print "0" }')

  jq -cn --arg name "$host" --arg role worker --arg os macos \
     --argjson reachable true --argjson ts "$(ts_ms)" \
     --argjson cpu_pct "${cpu_pct:-0}" --argjson gpu_pct "${gpu_res:-0}" \
     --argjson gpu_power_mw "${gpu_mw:-0}" \
     --argjson mem_used_bytes "${mem_used:-0}" --argjson mem_total_bytes "${mem_total:-0}" \
     --argjson disk_used_bytes "${disk_used:-0}" --argjson disk_total_bytes "${disk_total:-0}" \
     '{name:$name, role:$role, os:$os, reachable:$reachable, ts:$ts, cpu_pct:$cpu_pct,
       gpu_pct:$gpu_pct, gpu_power_mw:$gpu_power_mw,
       mem_used_bytes:$mem_used_bytes, mem_total_bytes:$mem_total_bytes,
       disk_used_bytes:$disk_used_bytes, disk_total_bytes:$disk_total_bytes}'
}

# ── token resolution: env var first, else 1Password (same chain as
# tools/claude-murakumo — never hardcode/commit the token) ──────────────────
if [ -z "${MURAKUMO_METRICS_TOKEN:-}" ] && command -v op >/dev/null 2>&1; then
  MURAKUMO_METRICS_TOKEN="$(op item get "gftd.murakumo/METRICS_TOKEN" --vault gftdcojp --fields credential --reveal 2>/dev/null || true)"
fi
if [ -z "${MURAKUMO_METRICS_TOKEN:-}" ]; then
  echo "hwmetrics-report: no token found. Set MURAKUMO_METRICS_TOKEN, or ensure" >&2
  echo "  \`op item get gftd.murakumo/METRICS_TOKEN --vault gftdcojp\` resolves" >&2
  echo "  (must match the api.murakumo.cloud Worker's MURAKUMO_METRICS_TOKEN secret)." >&2
  exit 1
fi

# every node probed CONCURRENTLY (background jobs) — serialized, 4 offline
# nodes alone would cost 4×ConnectTimeout=16s, blowing past the 10s report
# interval. In parallel, wall-clock ≈ the slowest single probe (~1-2s).
tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT
head_snapshot > "$tmpdir/head-gad.json" &
for w in "${WORKERS[@]}"; do
  worker_snapshot "$w" > "$tmpdir/worker-$w.json" &
done
wait

snapshots=()
for f in "$tmpdir"/*.json; do snapshots+=("$(cat "$f")"); done

payload=$(printf '%s\n' "${snapshots[@]}" | jq -cs --argjson ts "$(ts_ms)" '{ts:$ts, nodes:.}')

curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$MURAKUMO_API_URL" \
  -H "Authorization: Bearer $MURAKUMO_METRICS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "$payload"
