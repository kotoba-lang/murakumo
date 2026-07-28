# ADR-260728: W6 product-shell oracle authority — reconcile.plan pure scalars

Status: accepted after tunnel+config (#100) product-shell cutover

## Decision

Wire pure scalar helpers of `murakumo.reconcile.plan` to kotoba SSoT:

| layer | role |
|---|---|
| `kotoba/reconcile_plan_core.kotoba` | SSoT |
| `resources/murakumo/oracle/reconcile_plan_core.kir.edn` | precompiled KIR |
| `murakumo.kotoba.oracle` catalog `:reconcile-plan` | load + execute |
| JVM `desired` / `deficit` / `action-name` / `watch-sleep-ms` | delegate to oracle |

### Still host

- `eligible-nodes` / `observed-hosts` set algebra
- variable-length `pick-targets` sort (oracle has fixed 2/3 packs for parity)
- reason strings, CLI `parse-flags`, history records
- cljs host-mirror

## Evidence

- authority + reconcile_plan_kotoba_parity (+ unit tests)

## Related

- inventory Next: incremental host wiring after #99 bulk catalog
- murakumo#86–#100 product-shell pattern
