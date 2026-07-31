# ADR-260731: T5.2 native guest record — rebalance pairs + task max2/min2

- Status: accepted
- Date: 2026-08-01
- Depends: Progress 31et assign/reconcile tournament
- WBS: T5.2 residual multi-arg pure (rebalance i64 pairs + task max/min)

## Decision

| Export | Schema | Notes |
|--------|--------|-------|
| `pool-seats-of-*` / `seats-for-online-*` | `:rebalance/seats-in` | reuse seats-of shape |
| `seats-equal` / `pipeline-effective-gb` / `move-needed` / `rebalance-reason-code` / `take-count` / `pipeline-note` / `rebalance-reason-detail` | `:rebalance/pair` | a,b |
| `take-end` | `:rebalance/triple` | start,k,remaining |
| `max2` / `min2` (task) | `:task/pair` | residual |

## Evidence

- KIR regenerated rebalance + task
- Focused rebalance/task/authority: 90 tests / 1612 assertions green
