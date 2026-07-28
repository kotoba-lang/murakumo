# ADR-260728: W6 pure-planner oracle — task assign-step-3/summary + dash cap math

Status: accepted after n>3 host-fold (#80) + assign-step-3 schedule (#79)

## Decision

### `task_plan_core`

| export | role |
|---|---|
| `pick-task-fold-step` / `challenger-wins?` | n-ary task pick host-fold |
| `assign-task-pick-3` / `apply-task-pick-3` / `assign-task-step-3` | 3-node task assign step |
| `load-inc-if` | load update |
| `nearest-rank-idx` | percentile index (p-milli) |
| `summary-retried` / `speedup-milli` | summary pure counters |

### `dash_state_core`

| export | role |
|---|---|
| `take-last-start` / `append-new-len` / `cap-count` | append-capped index math |
| `recent-take-n` | recent-alerts n default |

Vector conj/concat stays host.

## Evidence

- task + dash parity: 14 tests / 120 assertions (combined ns run)

## Related

- inventory after #79–#80: pure assign reduce deepen + dash history
