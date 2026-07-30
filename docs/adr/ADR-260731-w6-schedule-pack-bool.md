# ADR-260731: schedule better2/3 pack fields + better-from/pair are :bool

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#222 (ranking bool), compiler#451

## Decision

1. **`:schedule/better2`** fields `warm0` / `warm1` / `better01` → `:bool`.
2. **`:schedule/better3`** fields `b01` / `b02` / `b12` → `:bool`.
3. **`better-from-queues`** / **`better-pair`** → `:bool` (aliases of score compare).
4. **`prefer-warm-then-score`**, **`pick-idx-2-full`**, **`pick-idx-3-tournament`**,
   **`pick-fold-step`** take warm/better as `:bool` (ok flags stay 0/1 `:i64` lanes).
5. **Rename** `better-task-score?` → **`task-score-code`** (0|1|2 ordinal, not a
   pure bool). All remaining `*?` predicates are `:bool`.

Warm triples used as queue/ok/warm still use `:i64` 0/1 lanes; `assign-pick-3`
projects warm to bool at the pick boundary.

## Evidence

- KIR regenerated for infer_schedule_core + task_plan_core
- schedule/task parity + authority green

## Invariant

Every kotoba product export whose name ends in `?` returns `:bool`.
Ordinal/pick codes do not use the `?` suffix.
