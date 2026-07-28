# ADR-260728: cljs dual-source for infer gc/relay/moe/join

Status: accepted after overlay+schedule cljs dual-source (#130)

## Decision

Expand incremental cljs host rewire:

| host | oracle id | dual-source |
|---|---|---|
| `murakumo.infer.gc` | `:infer-gc` | GiB/defaults, need/free/target, rank-better?, comfy-evictable? |
| `murakumo.infer.relay` | `:infer-relay` | make-id, msg kinds, lease-expired? |
| `murakumo.infer.moe` | `:infer-moe` | capacity-default, expert-ratio, verdict-name, resident-est |
| `murakumo.infer.join` | `:infer-join` | max-resident, can?, needs-relay?, clamp-resident, eligible-for-work? |

Plan folds / queue maps / custom capacity tiers stay host.

### Related prior

- #122–#130 cljs dual-source trail

### Still host / incremental

- infer plan/engine/rebalance/credits
- overlay runtime/driver
- deploy.plan

## Evidence

- gc/relay/moe/join parity/unit + authority green
- nbb smoke: ready? + pure helpers

## Related

- inventory Next: incremental cljs rewire (infer residual)
