# ADR-260728: W6 pure-planner oracle — infer plan usable-bytes + strategy

Status: accepted second cutover slice of `murakumo-pure-planners-v1`

## Decision

Port the integer-arithmetic core of `murakumo.infer.plan` to
`kotoba/infer_plan_core.kotoba`:

| function | notes |
|---|---|
| `gib` / `default-os-reserve` / `default-headroom` | exact i64 constants |
| `usable-bytes` | mem/os/head/wired (-1 = none) |
| `choose-strategy-name` | pipeline/tensor/expert as string |

### Not ported

- `partition-layers` / `plan` / `layer-weights` (float quotas + vector reduce)
- `largest-remainder`
- `report` (display)

## Evidence

- `test/murakumo/infer_plan_kotoba_parity_test.clj`
- Equality against `murakumo.infer.plan` on the offline unit corpus

## Related

- murakumo#37 kekkai gate oracle
- `lang/w6-murakumo-path-inventory.edn` first-cutover-slice
