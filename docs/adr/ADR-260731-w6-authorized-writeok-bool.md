# ADR-260731: authorized? / write-ok? are profile-5 :bool predicates

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#210 (failed?/can-retry? bool), compiler#451

## Decision

Continue the product-facing predicate cutover beyond task/schedule eligibility:

1. `kekkai_gate_core/authorized?` returns `:bool` (was 0/1 `:i64`).
2. `persist_core/write-ok?` returns `:bool` (was 0/1 `:i64`).
3. Hosts use `oracle/bool->host` (not `(= 1 (i64->host …))` and not Clojure
   `boolean`, which treats `0` as truthy).

Parity tests already used `(if (authorized? …) …)` or wrap write-ok as 0/1;
authority asserts `:bool` results with `contains? #{true 1}` / `#{false 0}`
where KIR may still surface the interior word.

## Evidence

- Live KIR regenerated for `kekkai_gate_core.kir.edn` + `persist_core.kir.edn`
- Focused kekkai/persist parity + authority + persist unit: 77 tests / 1258
  assertions, 0 failures

## Follow-up

- Remaining string helpers `blank?` / `ws?` across provision/reconcile/report/
  secret/overlay still return `:i64` 0/1 — convert when hosts stop treating
  them as numeric words
- Ranking / pick-code predicates (`better-*`, `rank-better?`) stay `:i64` by
  design (not pure booleans)
