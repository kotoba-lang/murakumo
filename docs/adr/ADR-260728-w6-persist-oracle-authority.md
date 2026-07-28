# ADR-260728: W6 product-shell oracle authority — persist pure path

Status: accepted after moe+rebalance+relay (#107) product-shell cutover

## Decision

Wire pure helpers of `murakumo.persist` to kotoba SSoT:

| layer | role |
|---|---|
| `kotoba/persist_core.kotoba` | SSoT |
| `resources/murakumo/oracle/persist_core.kir.edn` | precompiled KIR |
| `murakumo.kotoba.oracle` catalog `:persist` | load + execute |
| JVM constants, rkeys, repo-uri/url, write-ok? | delegate to oracle |

### Still host

- envelope maps / graph-cid hashing
- curl argv assembly, tunnel forward command
- cljs host-mirror

## Related

- murakumo#86–#107 product-shell pattern
