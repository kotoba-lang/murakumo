# hwmetrics-report

Collects one CPU/GPU/memory/disk snapshot of the live murakumo fleet (gad, the
AMD head, + the Apple M4 mesh workers) and POSTs it to
`api.murakumo.cloud/infer/hwmetrics`, which backs the live dashboard at
`https://murakumo.cloud/infer/hwmetrics/ui`.

## Where it runs

**On gad**, as a `systemd` timer (every 10s) — gad already holds SSH access to
every mesh node and the Cloudflare Tunnel that reaches `api.murakumo.cloud`,
so it's the natural place to run the collector rather than adding a new
egress path. See ADR-2607051700 for the systemd unit + secret provisioning.

## What it measures

- **gad (head, Linux/AMD)** — read locally, no SSH, no root: CPU busy % from
  a 200ms `/proc/stat` delta, GPU busy % + VRAM from
  `/sys/class/drm/card1/device/{gpu_busy_percent,mem_info_vram_*}` (amdgpu
  exposes these unprivileged), memory from `free -b`, disk from `df -k /`.
- **Each Apple M4 mesh worker** — one SSH round-trip running `sudo -n
  powermetrics --samplers cpu_power,gpu_power` (passwordless sudo already
  provisioned on these Macs) + `vm_stat`/`sysctl`/`df`. CPU% is the
  core-count-weighted average of E-Cluster/P-Cluster HW active residency;
  GPU% is GPU HW active residency. **These numbers run genuinely close to
  100% CPU on every node** — not a measurement artifact. The same 10 Apple M4
  minis (fleet.edn) also run `xmrig` as a standing Monero-mining fleet (see
  the `uriage` skill), so real CPU contention with the mining job is exactly
  what the dashboard is meant to surface.
- A worker that's asleep/offline (not all 10 are up at once — see
  ADR-2607051431) reports `{"reachable": false}`, not a dropped/missing node.

All ~11 nodes are probed **concurrently** (background jobs), not serially —
serial SSH with a 4s connect-timeout against 4 typically-offline nodes alone
would cost 16s, blowing past the 10s report interval. Concurrent, the whole
cycle takes ~2-4s.

## Roster

`WORKERS` in the script is a static mirror of `fleet.edn`'s `:host` list.
`fleet.edn` stays the single source of truth for the mesh roster — update
both if a node is added/removed/renamed.

## Env vars

| var | default | what |
|---|---|---|
| `MURAKUMO_API_URL` | `https://api.murakumo.cloud/infer/hwmetrics` | POST target |
| `MURAKUMO_METRICS_TOKEN` | resolved from 1Password (`gftd.murakumo/METRICS_TOKEN`, vault `gftdcojp`) if unset | must match the `api.murakumo.cloud` Worker's `MURAKUMO_METRICS_TOKEN` secret |

## Manual run

```bash
./hwmetrics-report.sh
```
