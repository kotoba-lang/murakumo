# ADR-260801: T5.2 native guest record — rem64 residual

- Status: accepted
- Date: 2026-08-01
- Depends: ct-scan residual (3150ff73)
- WBS: T5.2 residual multi-arg pure (last non-constructor i64 pair)

## Decision

Fold internal `rem64` (a rem b) multi-arg pure into existing pair records:

| Core | Export | Schema |
|------|--------|--------|
| `infer_plan` | `rem64` | `:plan/pair` |
| `infer_rebalance` | `rem64` | `:rebalance/pair` |
| `task_plan` | `rem64` | `:task/pair` |

Call sites pack via `record-new`. Constructors (`model-record`, `seats-record`,
…) remain multi-scalar by design (they *build* records).

## Non-claims

- T8.3 nested kit EDN still W4-gated
- T8.4 optional L5 / real Node sockets

## Evidence

- KIR regenerated plan/rebalance/task
- parity + authority 102/1710 green
