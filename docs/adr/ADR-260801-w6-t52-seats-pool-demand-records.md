# ADR-260801: T5.2 native guest record — pool-demand residual

- Status: accepted
- Date: 2026-08-01
- Depends: rem64 pair residual (f63c5bb8)
- WBS: T5.2 residual multi-arg pure (pool-demand)

## Decision

Fold `pool-demand-record` multi-scalar pure (5 class counts) into a single
guest record:

| Export | Schema (input) |
|--------|----------------|
| `pool-demand-record` | `:rebalance/demand` (`text`/`image`/`video`/`audio`/`postproc`) |

`demand-to-pool-record` passes the demand record through.

## Non-claims

- **`seats-record` stays multi-scalar** (max-arity 5). Folding it to
  `:rebalance/seats-in` exhausts the compiler lowering budget
  (`lowered program budget exhausted` at form 100001) because the body is
  already a large largest-remainder + bump tournament and is inlined into
  six seat projections. Not claimed as intentional forever — revisit after
  budget/inlining relief.
- Identity field constructors (`demand-record`, `model-record`, …) remain
  multi-scalar packers.
- T8.3 nested EDN still W4-gated; T8.4 L5 residual remains

## Evidence

- KIR regenerated `infer_rebalance_core`
- rebalance parity + authority green
