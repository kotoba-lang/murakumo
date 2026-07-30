# ADR-260731: task failed?/can-retry? are profile-5 :bool predicates

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#209 (task-eligible? bool), compiler#451

## Decision

1. `failed?` returns `:bool`; its `timeout` parameter is `:bool` (was 0/1 `:i64`).
2. `can-retry?` returns `:bool`.
3. Host uses `oracle/bool->host` for both; parity wraps with `(if … 1 0)`.

## Evidence

- Live KIR regenerated for `task_plan_core.kir.edn`
- task parity + authority + schedule tests green

## Follow-up

- String helpers `blank?` / `ws?` across provision/reconcile/report/secret still
  return `:i64` 0/1 — convert when hosts no longer treat them as numbers
