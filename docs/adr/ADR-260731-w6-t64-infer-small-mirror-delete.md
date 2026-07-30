# ADR-260731: T6.4 remainder — infer join/relay/gc/moe delete cljs mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after cauth #244)
- Depends: #244 component-authority, preload contract (#233)

## Decision

Delete dual-source cljs pure mirrors in four small infer product shells:

| ns | oracle id | host remains |
|---|---|---|
| `murakumo.infer.join` | `:infer-join` | tier map, partition-work fold |
| `murakumo.infer.relay` | `:infer-relay` | queue/worker state machine |
| `murakumo.infer.gc` | `:infer-gc` | plan fold over entries |
| `murakumo.infer.moe` | `:infer-moe` | custom capacity tiers, plan ranking |

All pure helpers use `oracle/require-ready!` on **every** platform.

## Non-claims

- `infer.credits` / `infer.plan` / `infer.schedule` / `infer.engine` /
  `infer.rebalance` still keep cljs mirrors (larger or heavier residual)
- `moe/verdict-name` still takes shared? as 0/1 i64 (param-bool residual)
- T8.3 production AOT; W4 recursive values

## Evidence

- join/relay/gc/moe host + parity suites green
- No dual-source mirror bodies remain in the four nses
