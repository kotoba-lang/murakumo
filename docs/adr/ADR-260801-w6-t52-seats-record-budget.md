# ADR-260801: T5.2 seats-record seats-in + lane projections (budget-safe)

- Status: accepted
- Date: 2026-08-01
- Depends: pool-demand residual (6f2d271b)
- WBS: T5.2 residual multi-arg pure (seats-record budget hold)

## Decision

1. **Public `seats-record`** takes `[:ref :rebalance/seats-in]` and returns
   `:rebalance/lanes`.
2. **Internal `seats-record-go`** keeps the multi-scalar largest-remainder body
   (max-arity 5). Folding that body *into* every projection exhausts the
   compiler `max-lowered-nodes` budget (100000) when the body is inlined six
   times.
3. **`seats-of-*` / `pool-seats-of-*`** take **lanes** and are field
   projections only. Host `largest-remainder` calls `seats-record` once, then
   projects three lanes.
4. **`seats-for-online-record`** replaces the three `seats-for-online-*`
   exports (same online seats-in → lanes once). Tests project with
   `seats-of-*`.

## Non-claims

- `seats-record-go` remains multi-scalar internal (not an exported residual
  packer for hosts).
- T8.3 nested EDN still W4-gated; T8.4 L5 residual remains.

## Evidence

- KIR regenerated `infer_rebalance_core`
- rebalance parity 10/91 + authority green
