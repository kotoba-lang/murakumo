# ADR-260731: T6.4 remainder — schedule/rebalance/engine delete cljs mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after driver/runtime #246)
- Depends: #246 overlay driver+runtime, preload contract (#233)

## Decision

Delete dual-source cljs pure mirrors in three infer product shells:

| ns | oracle id | host remains |
|---|---|---|
| `murakumo.infer.schedule` | `:infer-schedule` | set membership, pick sort-by, assign atom fold |
| `murakumo.infer.rebalance` | `:infer-rebalance` | demand fold, placement assignment, hysteresis |
| `murakumo.infer.engine` | `:infer-engine` | plan vector walks, CSV joins, pr-str prompt, extra-args |

All pure helpers use `oracle/require-ready!` on **every** platform.

## Non-claims

- `infer.credits` / `infer.plan` still keep cljs mirrors
- engine opt flags still project presence as 0/1 i64 into guest
  (`opt-i64-flag` / cache?) — param-bool residual
- T8.3 production AOT; W4 recursive values

## Evidence

- schedule / rebalance / engine host + parity suites green
- No dual-source mirror bodies remain in the three nses
