# ADR-260728: cljs dual-source for infer plan/engine/rebalance/credits

Status: accepted after infer gc/relay/moe/join (#131)

## Decision

Complete residual infer pure dual-source on cljs/nbb:

| host | oracle id | dual-source |
|---|---|---|
| `murakumo.infer.plan` | `:infer-plan` | GiB/defaults, usable-bytes, choose-strategy-name |
| `murakumo.infer.engine` | `:infer-engine` | rpc-server-cmd, endpoints, head/mlx/embed cmd fragments |
| `murakumo.infer.rebalance` | `:infer-rebalance` | shard-ceiling, usable-gb, largest-remainder-3 |
| `murakumo.infer.credits` | `:infer-credits` | per-token, head/protocol fracs, memory-time-weight, charge-allow? |

Partition/plan folds, float settle, transfer maps stay host.

### Completes

With #122–#132 + this slice, **all** previously clj-only product-shell pure hosts that lived in `.cljc` dual-source when oracle ready (report remains JVM `.clj`).

## Evidence

- plan/engine/rebalance/credits parity/unit + authority green
- nbb smoke: ready? + usable-bytes/capacity/charge helpers

## Related

- inventory Next: Delivery 5–8 / network·secret / residual PVA
