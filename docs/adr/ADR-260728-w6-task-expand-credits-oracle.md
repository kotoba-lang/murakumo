# ADR-260728: W6 pure-planner oracle — task expand/assign-step + credits multi-unit shares

Status: accepted after assign-step/task-eligible (#76) + plan n≠3 (#77)

## Decision

### `task_plan_core` — expand + assign-step-2

| export | role |
|---|---|
| `pad4` / `task-id` | `t-XXXX` expand ids (last-4 zero-pad) |
| `attempt-next` | retry attempt inc |
| `pick-task-idx-2` / `assign-task-step-2` | one-task pure pick + load for 2 nodes |

### `infer_credits_core` — multi-unit cost + floor shares

| export | role |
|---|---|
| `unit-cost` / `job-cost-2` / `job-cost-3` | integer multi-unit totals |
| `share-floor` / `share-pack-2` / `settle-pool-shares-2` | pool floor shares |
| `mt-sum-2` / `mt-sum-3` | memory-time weight sums |

Remainder redistribution and unknown-unit throws stay cljc/host.

## Evidence

- `test/murakumo/task_plan_kotoba_parity_test.clj`
- `test/murakumo/infer_credits_kotoba_parity_test.clj`
- Combined run: 13 tests / 99 assertions

## Related

- inventory Next after #76–#77: full assign reduce / economy pure deepen
