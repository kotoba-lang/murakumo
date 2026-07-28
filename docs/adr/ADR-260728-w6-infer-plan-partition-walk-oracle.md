# ADR-260728: W6 pure-planner oracle — infer.plan partition-layers walk

Status: accepted product vertical after plan-lr-3 / layer-bytes (#67)

## Decision

Extend `infer_plan_core.kotoba` with the integer `partition-layers` walk for a
fixed 3-node ring:

| export | role |
|---|---|
| `model-pack` / `model-layers` / `model-dense` / `model-frac-milli` | pack decoder meta |
| `layer-byte-at` / `layer-wsum` | integer layer-weights |
| `partition-target` | cumulative byte target = wsum·cum/total |
| `advance-hi` | inner cut loop (stop before exceeding target) |
| `est-bytes-range` | sum of layer bytes over [lo, hi) |
| `partition-3-ends` | pack3 exclusive layer ends for 3 nodes |

Dense fraction uses milli (100 → 0.1). Matches cljc doubles on uniform and
typical MoE fixtures.

### Still cljc

- variable-arity node vectors (n ≠ 3)
- `plan` map assembly with node ids / `:assignments` records
- `report` human rows
- float layer vectors as first-class guest values

## Evidence

- `test/murakumo/infer_plan_kotoba_parity_test.clj`

## Related

- murakumo#67 plan-lr-3 + fits + layer-bytes scalars
- murakumo#38 usable-bytes / choose-strategy
