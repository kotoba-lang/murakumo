# ADR-260731: split ok/warm flag triples from i64 queue/fill triples

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#229 (bump ok bool), #227 (pick ok bool), compiler#451

## Decision

Multi-role `:schedule/triple` / `:task/triple` previously packed **queue counts
and 0/1 flags** in the same `:i64` lanes. Split flag roles into typed records:

1. **`:schedule/flags3`** / **`:task/flags3`** — `[[:v0 :bool] [:v1 :bool] [:v2 :bool]]`
   for ok / warm eligibility.
2. **`flags3-record`** constructors (+ schedule `flags3-v0/v1/v2` projectors).
3. **`assign-pick-3` / `assign-step-3`** take ok/warm as `flags3` (queue stays
   `triple` i64).
4. **`assign-task-pick-3` / `assign-task-step-3`** take ok as `flags3` (fill/load
   stay `triple` i64).

No more `(if (= (record-get ok :v0) 1) true false)` projection at pick boundaries.

## Still i64

- Queue / fill / load / assign **codes** and seat arithmetic
- `task-score-code` ordinal (0|1|2)

## Evidence

- KIR regenerated for schedule + task
- 88 tests / 1584 assertions, 0 failures
