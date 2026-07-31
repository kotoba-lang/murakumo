# ADR-260801: T5.2 native guest record — assign-step-3 + demand-inc residual

- Status: accepted
- Date: 2026-08-01
- Depends: pool-demand residual (6f2d271b)
- WBS: T5.2 residual multi-arg pure (assign3 multi-record + demand-inc)

## Decision

Fold remaining multi-record / multi-arg pure assign and demand steps into
single guest records:

| Core | Export | Schema |
|------|--------|--------|
| `infer_rebalance` | `demand-inc` | `:rebalance/demand-step` |
| `infer_schedule` | `assign-pick-3` | `:schedule/pick3-in` |
| `infer_schedule` | `apply-pick-3` | `:schedule/apply-pick3` |
| `infer_schedule` | `assign-step-3` | `:schedule/assign3-in` |
| `task_plan` | `assign-task-pick-3` | `:task/pick3-in` |
| `task_plan` | `apply-task-pick-3` | `:task/apply-pick3` |
| `task_plan` | `assign-task-step-3` | `:task/assign3-in` |

Mirrors the assign-step-2 / assign2-in pattern for 3-node assign.

## Non-claims

- `seats-record` still multi-scalar (compiler lowering budget; ADR pool-demand)
- Identity field constructors (`*-record` packers) remain multi-scalar
- T8.3 nested EDN still W4-gated; T8.4 L5 residual remains

## Evidence

- KIR regenerated rebalance/schedule/task
- parity + authority 98/1685 green
