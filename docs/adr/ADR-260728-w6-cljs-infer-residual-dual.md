# ADR-260728: cljs dual-source for residual infer plan/engine/rebalance/credits

Status: accepted after infer gc/relay/moe/join (#131) + overlay runtime/driver/deploy (#132)

## Decision

Complete residual infer/* cljs host rewire:

| host | oracle id | dual-source |
|---|---|---|
| `murakumo.infer.plan` | `:infer-plan` | GiB/defaults, usable-bytes, choose-strategy-name |
| `murakumo.infer.engine` | `:infer-engine` | rpc-port, rpc-server-cmd, endpoint/i64-str, head/mlx/moe/embed cmd fragments |
| `murakumo.infer.rebalance` | `:infer-rebalance` | shard-ceiling, usable-gb, largest-remainder-3 seats |
| `murakumo.infer.credits` | `:infer-credits` | default-per-token, head/protocol fracs, memory-time-weight, charge-allow? |

Partition/placement folds, float settle/transfer, plan map assembly stay host.

### Related prior

- #122–#132 cljs dual-source trail

### Still host / incremental

- report (JVM clj shell)
- Delivery 5–8 shells / residual PVA
- network·secret caps contract-only

## Evidence

- plan/engine/rebalance/credits unit + parity green
- nbb smoke: ready? + usable-bytes/default-rpc-port/shard-ceiling/default-per-token

## Related

- inventory Next: Delivery shells; pure cljs rewire of product-shell pure cores complete for portable .cljc hosts
