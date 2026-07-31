# ADR-260731: T5.2 native guest record wire — task tournament residual

- Status: accepted
- Date: 2026-07-31
- Depends: schedule residual + pick-fold-step (59617f0e)
- WBS: T5.2 residual multi-arg pure (task tournament)

## Decision

| Export | Schema |
|--------|--------|
| `capacity` | `:task/capacity-in` |
| `pick-task-idx-2` | `:task/pick2` |
| `assign-task-step-2` | `:task/assign2-in` |

`slots` composes `capacity` via `record-new`. 3-node assign already uses
flags3/triple records.

## Non-claims

- Digit scanners optional residual
- plan better-bump?/plan-lr multi-arg residual remains
- T8.3 nested EDN still W4-gated

## Evidence

- KIR regenerated `task_plan_core`
- task parity + authority + call-record: 100 tests / 1746 assertions green
