# Fleet health — monitor, evaluate, stabilise

`murakumo health` (CLI) and `murakumo.fleet_health | fleet_slo | fleet_heal` (MCP)
are one implementation, `src/murakumo/health.cljk`, appending to one ledger.

```sh
kbb --backend sci --classpath src scripts/health.cljk check --canary   # from a checkout
scripts/run-task.cljk health check                                     # same, as a task
murakumo health check --canary --exit-code                             # node CLI (after build-node-cli.sh)
claude mcp add murakumo -- nbb --classpath "src:../org-anthropic-mcp/src" scripts/mcp-server.cljk
```

| command | what it answers |
|---|---|
| `check [--nodes a,b] [--canary] [--no-record] [--json] [--exit-code]` | Is the fleet healthy *now*: verdict, 0-100 score, findings per node and gateway |
| `slo [--window N]` | Has it been stable: per-node availability, **flapping** nodes, gateway availability, canary baseline |
| `history [--limit N]` | The raw series |
| `heal [--node N] [--apply]` | What could be fixed, and (only with `--apply`) the declared fixes |
| `watch [--interval 300] [--canary] [--heal]` | Loop `check` (and optionally `heal`) in the foreground |

`--exit-code` returns 0 healthy / 1 degraded / 2 critical, so cron or launchd can alert on it.

## What is probed

One `ssh` per node, all in parallel from a single local script (≈10 s for 15 nodes):
load per core, root filesystem use and free GB, uptime (recent reboot), TIME_WAIT
pressure of the ephemeral port range (a node at ~100% fails its own probes while its
server is fine), kotoba-server `/health`, and **the mishima llama-server's own `/health`**
on its real listen address (it binds the tailnet IP, so loopback would miss it).
Gateway: `/health`, `/v1/models`, and with `--canary` one tiny completion whose tok/s and
latency are compared to the **median of the last 10 canaries in the ledger**. With no
history there is no regression verdict rather than an invented one.

## What "expected" means (so nothing is falsely red)

- **mesh** is expected only where a `*murakumo-mesh*` LaunchAgent plist is installed
  (or `:health/expect-mesh true`). After the 2026-09-11 media cutover several nodes
  correctly serve with no mesh.
- **serving** is expected where `fleet.edn` says `:node/serves "mishima"`, on port 8094
  unless the node declares `:health/serve-port` (aiueos-6600hs-1 uses 8093).
- an unreachable node reports only that; nothing else about it is invented.

## Stability: heal, and its guards

`heal` never guesses a command. Over ssh these nodes cannot use `launchctl`
(`Domain does not support specified action`), so a hard-coded "kickstart the agent"
would be wrong exactly where it matters. The only thing `--apply` runs is the
`:health/restart-cmd` a node **declares in its own `fleet.edn` entry** — a command the
owner has verified for that node:

```clojure
{:name "gad" ... :health/restart-cmd "systemctl --user restart mishima.service"}
```

Even then it is refused when
- the node was healed in the last 30 min (a restart that did not hold is a finding,
  not a retry), or
- a third or more of the expected nodes are down together (a shared cause — restarting
  each would erase the evidence and the surviving capacity).

Unreachable, port exhaustion, load and disk are advice only.

## Ledger

`$MURAKUMO_HEALTH_LEDGER` (default `~/.local/share/murakumo/health-ledger.jsonl`).
Append-only JSONL; unparseable lines are counted, never silently dropped. Rows are
`snapshot` (verdict, score, status per node, gateway + canary) or `heal`.

## Not covered

- No fleet-wide *concurrent* throughput run — that is `scripts/mishima-fleet-bench.py`
  and it loads every node; the canary is one request through the gateway.
- Long-context (cold 17k-token prefill) stability is still the open item from
  `verify/evidence/mishima-hermes-primary-stability-20260922.json`; the canary is a
  short prompt and will not show it.
