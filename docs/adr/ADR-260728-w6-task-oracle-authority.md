# ADR-260728: W6 product-shell oracle authority — task.plan pure path

Status: accepted after infer.schedule (#94) product-shell cutover

## Decision

Wire high-traffic pure helpers of `murakumo.task.plan` to kotoba SSoT:

| layer | role |
|---|---|
| `kotoba/task_plan_core.kotoba` | SSoT |
| `resources/murakumo/oracle/task_plan_core.kir.edn` | precompiled KIR |
| `murakumo.kotoba.oracle` catalog `:task-plan` | load + execute |
| JVM `slots` / `failed?` / `eligible?` / `task-id` / retry bounds / wave·slot / percentile idx / summary retried·speedup | delegate to oracle |

### Still cljc host

- admit / prepare / trim-to-budget folds
- set/map membership projection for labels, roles, exclude, allowlist → bit flags
- `node-score` float sort keys + stable `sort-by`
- final-results group-by / map assembly
- cljs host-mirror for pure helpers

## Evidence

- authority + task_plan_kotoba_parity (+ existing task plan tests)

## Related

- inventory Next: expand catalog (engine/…)
- murakumo#86–#94 product-shell pattern
