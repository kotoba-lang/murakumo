# ADR-260731: ranking better-*/rank-better? return profile-5 :bool

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#219 (char/eq residual), compiler#451

## Decision

Convert pairwise ranking predicates that are true 0/1 gates to `:bool`:

1. `rank-better?`, `better-score?`, `better-target?`, `better-bump?` (plan + rebalance),
   `before-pool?`, `better-fill?`, `better-mem?`, `challenger-wins?` → `:bool`.
2. Call sites that pick indices use `(if (better-…?) …)` instead of `(= … 1)`.
3. **Keep ternary / pack helpers as `:i64`**:
   - `better-task-score?` returns 0 | 1 | 2 (tie) — not a pure bool
   - `better-from-queues` / `better-pair` stay 0/1 `:i64` for better2/better3 record packing
4. Hosts use `oracle/bool->host` where dual-sourced (gc `rank-better?`).

This supersedes earlier “ranking stays :i64 by design” notes for pure pairwise
comparators only. Index pick codes and ternary scores remain numeric.

## Evidence

- KIR regenerated for schedule / task / reconcile / plan / rebalance / gc
- Focused parity green (see commit)

## Residual non-bool `?` surface

Only `better-task-score?` (ternary). All other `*?` product predicates are `:bool`.
