# ADR-260728: W6 product-shell oracle authority — infer.plan pure path

Status: accepted fourth product-shell dual-source cutover after report (#89)

## Decision

Wire high-traffic pure helpers of `murakumo.infer.plan` to kotoba SSoT:

| layer | role |
|---|---|
| `kotoba/infer_plan_core.kotoba` | SSoT |
| `resources/murakumo/oracle/infer_plan_core.kir.edn` | precompiled KIR |
| `murakumo.kotoba.oracle` catalog `:infer-plan` | load + execute |
| JVM `GiB` / defaults / `usable-bytes` / `choose-strategy` name | delegate to oracle |

### Still cljc host

- `layer-weights` / `partition-layers` float walk
- `plan` map assembly with node ids
- `choose-strategy` `:why` strings (host table keyed by oracle strategy name)
- cljs host-mirror for pure helpers

## Evidence

- authority + infer_plan_kotoba_parity — 25 tests / 183 assertions

## Related

- inventory Next: expand catalog (infer/plan/…)
- murakumo#86–#89 product-shell pattern
