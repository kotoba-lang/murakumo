# ADR-260731: task-eligible? is a profile-5 :bool predicate

- Status: accepted
- Date: 2026-07-31
- Depends: kotoba-lang/compiler#451, murakumo#207 (schedule eligible? bool)

## Decision

Mirror schedule's profile-5 eligibility cutover for `task_plan_core`:

1. `:task/eligibility` fields are `:bool` (`online`, `labels-ok`, `roles-ok`,
   `not-excluded`, `allowlist-ok`).
2. `task-eligible?` returns `:bool` via nested `if` (gensym-stable for the
   precompiled KIR drift gate).
3. Host `murakumo.task.plan` projects placement membership into real booleans
   and maps guest results with shared `oracle/bool->host` (not Clojure
   `boolean`, which treats `0` as truthy).

`failed?` / `can-retry?` stay `:i64` 0/1 in this slice — they feed numeric
assign paths; convert later if callers want predicates.

## Evidence

- Live KIR regenerated for `task_plan_core.kir.edn`
- `oracle/bool->host` shared; schedule host reuses it
- task unit + parity + authority tests green

## Follow-up

- Other `*?` pure planners (`failed?`, `can-retry?`, better-* when pure
  predicates) when callers no longer depend on 0/1 words
