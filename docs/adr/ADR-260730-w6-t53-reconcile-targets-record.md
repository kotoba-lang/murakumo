# ADR: T5.3 (reconcile) — pick-targets result becomes a record

- Status: Accepted
- Date: 2026-07-30
- WBS: T5.3 residual after credits shares (murakumo#200)
- Superproject: ADR-2607299400 P2

## Context

`pick-targets-2-pack` returned placement indices as one i64:

```
first | second*256 | count*65536
```

Host product path sorts candidates in cljc and never called the pack; only
parity used it. Mixed radix packing is a pure-product forbidden pattern.

## Decision

```
:reconcile/targets [[:first :i64] [:second :i64] [:count :i64]]
pack-targets / pick-targets-2-pack → targets-record / pick-targets-2-record
target-pack-* → targets-first / targets-second / targets-count
```

`name-bits` for 3-way name order stays an i64 bitmask (host projection of
three pairwise name comparisons into max-parameters budget), not a result pack.

## Evidence

- reconcile parity: 5 tests / 60 assertions / 0 failures
- KIR regenerated; live == resource
