# ADR-260728: W6 product-shell oracle authority — infer.join + infer.gc

Status: accepted after identity+credits (#103)

## Decision

Host-wire catalog ids `:infer-join` and `:infer-gc`:

| catalog | host | pure delegates |
|---|---|---|
| `:infer-join` | `murakumo.infer.join` | max-resident-bytes, can?, needs-relay?, clamp-resident, eligible-for-work? |
| `:infer-gc` | `murakumo.infer.gc` | GiB/defaults, need-bytes, free-after, target-met?, comfy-evictable? |

### Still host

- tier maps (install/runtime/connect metadata)
- partition-work / plan candidate folds
- cljs host-mirrors

## Evidence

- authority + join/gc parity + unit tests

## Related

- murakumo#99 bulk catalog; incremental host wiring trail
