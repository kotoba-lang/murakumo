# ADR-260731: T5.2 call-record expand wave 9

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#261–#272
- WBS: T5.2 remainder — positional projection expansion

## Decision

Expand `oracle/call-record` after wave 8 (#272 task/moe/join/gc):

| Host | Export |
|------|--------|
| `infer.engine` | `rpc-server-cmd`, `i64-str`, `endpoint`, `head-cmd-front/middle/tail`, `mlx-launch-front`, `mlx-moe-front`, `opt-i64-flag`, `opt-str-flag`, `embed-head-front/back` |
| `infer.schedule` | `score-queue`, `score-free`, `queue-inc-if` |
| `task.plan` residual | `wave-of`, `slot-of`, `load-after-assign`, `task-id`, `attempt-next`, `nearest-rank-idx`, `summary-retried`, `speedup-milli` |

## Non-claims

- Guest export signatures unchanged.
- Native guest `[:record …]` wire remains T5.3 path.
- T8.3 nested kit EDN codec remains W4-gated.
- `infer.schedule/eligible?` keeps T5.3 `oracle/record` eligibility + positional free/min (not rewritten).

## Evidence

- `oracle-call-record-test` + engine/schedule/task suites green
