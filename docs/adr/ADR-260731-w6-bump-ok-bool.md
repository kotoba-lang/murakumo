# ADR-260731: plan/rebalance pick-bump ok-for flags are :bool

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#227 (pick ok flags), compiler#451

## Decision

1. **`ok-for`** → `[active :bool bumped :bool] :bool` in both
   `infer_plan_core` and `infer_rebalance_core`.
2. **`pick-bump-3`** → `ok-t` / `ok-m` / `ok-p` are `:bool`.
3. Active lanes use **`weight-pos?`** directly (no 0/1 projection) before
   `ok-for`; bumped trackers (`t0`/`m0`/…) are `:bool` after each remainder
   pick.
4. Seat/lane **counts** stay `:i64` arithmetic.

## Residual multi-role triples

`:schedule/triple` and `:task/triple` still pack queue/ok/warm as `:i64` 0/1
lanes (queue counts share the shape). Splitting would be a record redesign,
not a predicate cutover.

## Evidence

- KIR regenerated for plan + rebalance
- 92 tests / 1602 assertions, 0 failures
