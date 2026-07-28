# ADR-260728: W6 pure-planner oracle — task plan integer core

Status: accepted fourth cutover slice of `murakumo-pure-planners-v1`

## Decision

Port the integer-arithmetic core of `murakumo.task.plan` to
`kotoba/task_plan_core.kotoba`:

| function | notes |
|---|---|
| `default-max-slots` / `default-max-attempts` / `default-timeout-ms` | exact i64 defaults |
| `slots` | budget override or max(1, min(max-slots, capacity)); -1 = absent |
| `failed?` | exit/timeout/error → 0/1 |
| `can-retry?` | attempt < max-attempts |

### Not ported

- `admit` / `prepare` / `trim-to-budget` (float load, map reduce)
- `eligible?` / `assign` / `node-score` (maps, vectors, float scores)
- `expand` / `retry-tasks` / `summary` (collections)

## Evidence

- `test/murakumo/task_plan_kotoba_parity_test.clj`
- Equality against `murakumo.task.plan` offline unit corpus

## Related

- murakumo#37–#39 (gate / infer-plan / dash-state)
- `lang/w6-murakumo-path-inventory.edn` first-cutover-slice
