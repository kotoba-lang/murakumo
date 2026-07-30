# ADR-260731: T6.4 remainder — load-time + residual call-site JVM require

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4
- Depends: #226 fleetwide try-oracle

## Decision

Close the **load-time soft-fallback** gap left open by
`ADR-260731-w6-t64-oracle-required-fleetwide` (non-claim: ad-hoc `def` try/if).

### Load-time constants

Replace dual-platform soft `try`/`if (oracle/ready? oid)` with the same contract
as `oracle-str-const` / `oracle-i64-const`:

| host | change |
|---|---|
| `config` | `oracle-const` → `oracle-str-const` |
| `secret` | `oracle-const` → `oracle-str-const` |
| `infer.engine` | `default-rpc-port` → `oracle-i64-const` |
| `infer.rebalance` | `shard-ceiling-gb` → `oracle-i64-const` |
| `infer.gc` | `GiB` + `default-policy` fields → `oracle-i64-const` |
| `infer.credits` | `default-per-token` → `oracle-i64-const`; head/protocol frac → `oracle-ratio-const` |
| `infer.join` | `max-res-for-tier` JVM throw / cljs mirror |

### Residual call sites

Ad-hoc `if (oracle-ready?)` runtime pure paths → `try-oracle`:

- `fleet.inventory`: `node-port`, `node-health-url`, `select`, `parse-tailscale-status`
- `dash.state`: `short-hosted-cid`, `health-class`, `interval-sleep-ms`, `clamp-at`,
  `recent-alerts`, `append-capped`
- `task.plan`: add `try-oracle`; `why-unschedulable`, `failed?`

## Contract (unchanged)

- **:clj** — throw if oracle not ready (T6.2 shipped KIR on prod classpath)
- **:cljs** — mirror fail-closed fallback when not ready / call fails

## Non-claims

- cljs `mirror-*` body deletion still open (needs entrypoint preload guarantee)
- T8.3 production AOT network/secret; W4 recursive values
