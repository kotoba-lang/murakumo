# ADR: T5.3 (credits) — two-lane floor shares become a record

- Status: Accepted
- Date: 2026-07-30
- WBS: T5.3 residual after schedule assign packs (murakumo#199)
- Superproject: ADR-2607299400 P2

## Context

`infer_credits_core/share-pack-2` returned floor shares as one i64:

```
s0 + s1 * 65536
```

Shares for large pools can exceed 65536 (the comment admitted "tests use
small"), so the packing was both a forbidden pattern and lossy. Host
`settle` stays float in cljc and never called the pack; only the pure
oracle surface and parity tests used it.

## Decision

```
:credits/shares2 [[:s0 :i64] [:s1 :i64]]
share-pack-2        → share-record-2
settle-pool-shares-2 → returns the same record
share-of-0 / share-of-1 → field projections
```

No base-65536 remains in the credits pure module.

## Evidence

- credits parity: 5 tests / 31 assertions / 0 failures
- KIR regenerated; live == resource

## Non-goals

- Host float settle path (unchanged)
- job-cost-3 arity-5 extra precompute (not a pack; record of units is optional later)
- rebalance / task / reconcile packs (separate slices)
