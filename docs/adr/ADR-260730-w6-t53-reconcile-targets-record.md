# ADR: T5.3 (reconcile) — placement pick targets become a record

- Status: Accepted
- Date: 2026-07-30
- WBS: T5.3 residual after schedule assign (#199) and credits shares (#200)

## Context

`pick-targets-2-pack` returned a mixed-radix i64:

```
first | second*256 | count*65536
```

with `target-pack-first/second/count` unpackers. Indices are 0..2 and count 0..2,
so the base-256 / base-65536 encoding worked, but it is a public base-N pack —
forbidden by T5.1 for new APIs and the next residual after plan/schedule/credits.

Host product path (`murakumo.reconcile.plan/pick-targets`) sorts candidates in
cljc and never called the pack APIs; they lived for pure 2/3-candidate
tournament parity.

## Decision

Named schema:

```
:reconcile/targets [[:first :i64] [:second :i64] [:count :i64]]
```

| Old | New |
|---|---|
| `pack-targets` | `targets-record` |
| `pick-targets-2-pack` | `pick-targets-2-record` |
| `target-pack-first/second/count` | `targets-first/second/count` |

`pick-targets-3-first` stays a scalar index (first among 3); multi-pick for n>1
still re-calls the 2-candidate path on the remaining pair.

**Not in this slice:** `name-bits` (3 pairwise name-order flags as bit0/1/2) —
same shape as pre-T5.3 schedule eligibility flags; convert when bool/record
surface makes three named fields cheap.

## Evidence

- reconcile parity suite green (see PR)
- precompiled KIR regenerated
- host doc comment only (no dual-source call path for packs)

## Non-goals

- bool-typed `better-target?` (returns 0/1 i64)
- name-bits → record of three bools
- host sort replacement with tournament oracle on product path
