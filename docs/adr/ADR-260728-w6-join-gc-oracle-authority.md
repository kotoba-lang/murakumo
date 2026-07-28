# ADR-260728: W6 product-shell oracle authority — infer.join + infer.gc pure paths

Status: accepted after identity+credits (#103) product-shell cutover

## Decision

Wire pure helpers of two catalog-only cores to kotoba SSoT:

| catalog id | host | SSoT / KIR |
|---|---|---|
| `:infer-join` | `murakumo.infer.join` | `infer_join_core.kotoba` → `infer_join_core.kir.edn` |
| `:infer-gc` | `murakumo.infer.gc` | `infer_gc_core.kotoba` → `infer_gc_core.kir.edn` |

### join (JVM)

- `max-resident-bytes` tier caps on `tiers` map
- `can?` / `needs-relay?` via tier-code projection
- `clamp-resident` in enrollment
- `eligible-for-work?` host-projected can-kind flag

### gc (JVM)

- `GiB` / `default-policy` constants (`default-target-free`, comfy-keep, hf-keep)
- `need-bytes` / `free-after` / `target-met?`
- `rank-better?` comparator for eviction order
- `comfy-evictable?` for comfy-temp filter

### Still host

- join: partition-work vector folds, enrollment map shell, cljs
- gc: entry class filters, hf-lru drop, reduce-until-need, cljs

## Evidence

- authority + join/gc kotoba parity (+ unit tests)

## Related

- murakumo#86–#103 product-shell pattern
