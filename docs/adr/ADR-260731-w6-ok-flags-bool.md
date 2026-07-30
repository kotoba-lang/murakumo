# ADR-260731: pick ok flags are profile-5 :bool

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#224 (better2/3 pack bool), compiler#451

## Decision

Convert remaining **eligibility ok** parameters on pick/assign helpers from
0/1 `:i64` to `:bool`:

1. **schedule**: `pick-idx-2-full` / `pick-idx-3-tournament` / `assign-step-2` /
   `pick-fold-step` take `ok*` as `:bool`.
2. **task**: `pick-task-idx-2` / `assign-task-step-2` / `pick-task-fold-step`
   take `ok*` / `better-c-i` as `:bool`.
3. Multi-role **triples** (`:schedule/triple`, `:task/triple`) stay `:i64` lanes
   (queue counts + 0/1 flags share one shape). Call sites that need bools
   project `(if (= v 1) true false)` at the pick boundary
   (`assign-pick-3`, `assign-task-pick-3`).

## Still numeric by design

- Queue / load / fill / pick **codes** (`task-score-code`, `pick-code`, assign
  result codes)
- plan/rebalance `pick-bump-3` ok/bump flags (tightly coupled to remainder
  arithmetic; follow-up if split)

## Evidence

- KIR regenerated for schedule + task
- 88 tests / 1584 assertions, 0 failures
