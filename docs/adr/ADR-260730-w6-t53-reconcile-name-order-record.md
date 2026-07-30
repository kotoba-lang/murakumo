# ADR: T5.3 (reconcile) — name-bits become a name-order record

- Status: Accepted
- Date: 2026-07-30
- WBS: T5.3 residual after targets (#202) and task eligibility (#205)

## Context

`first-of-3` / `pick-targets-3-first` took `name-bits` packing three pairwise
name-order flags:

```
bit0 = n0<=n1 (mask 1) | bit1 = n0<=n2 (mask 2) | bit2 = n1<=n2 (mask 4)
```

Host product path sorts candidates in cljc and never called these APIs; the
pack lived for pure tournament parity only. After targets became a record,
this was the last bit-pack in `reconcile_plan_core`.

## Decision

```
:reconcile/name-order [[:n01 :i64] [:n02 :i64] [:n12 :i64]]
```

`name-order-record` constructs it. `first-of-3` / `pick-targets-3-first` take
the record. `bit?` / `rem64` deleted. Module free of base-N and bit packs.

## Evidence

- reconcile parity: 5 / 60 / 0 failures
- authority: 66 / 1175 / 0 failures
- KIR regenerated
