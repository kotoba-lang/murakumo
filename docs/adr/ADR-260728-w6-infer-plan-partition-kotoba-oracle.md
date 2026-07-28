# ADR-260728: W6 pure-planner oracle — infer.plan LR + fits (partition vertical)

Status: accepted product vertical slice after rebalance path complete

## Decision

Extend `infer_plan_core.kotoba` beyond usable-bytes / choose-strategy with:

| export | role |
|---|---|
| `plan-lr-3` | integer `largest-remainder` over 3 rank weights (no per-pool floor) |
| `plan-lr-pack-get` | unpack base-65536 seat pack |
| `plan-fits-total?` / `span-fits?` | plan go/no-go partial gates |
| `uniform-layer-bytes` / `dense-units-milli` / `moe-layer-bytes` | layer-weights scalar core |

### Not ported

- full `partition-layers` cumulative float walk over layer vectors
- `plan` map assembly with node ids
- variable-arity largest-remainder beyond 3 ranks

## Evidence

- `test/murakumo/infer_plan_kotoba_parity_test.clj`

## Related

- murakumo#38 usable-bytes/choose-strategy first slice
- murakumo#61 rebalance largest-remainder-3 (with floor; different algorithm)
