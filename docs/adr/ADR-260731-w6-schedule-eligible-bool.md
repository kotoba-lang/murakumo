# ADR-260731: schedule eligible? is a profile-5 :bool predicate

- Status: accepted
- Date: 2026-07-31
- Depends: kotoba-lang/compiler#451 (language profile 5), T5.3 schedule eligibility record (#195)

## Decision

`kotoba/infer_schedule_core.kotoba` eligibility is the ADR 0191 showcase:

1. `:schedule/eligibility` fields are `:bool` (not 0/1 `:i64`).
2. `eligible?` returns `:bool` and is written with `and` / `or` / `not` /
   `>=` instead of nested `(if (= flag 0) 0 …)`.
3. `holds-warm?` returns `:bool` (the holds-checkpoint field).
4. Host `murakumo.infer.schedule` projects set membership into real booleans
   via `oracle/record` (`:bool` case) and treats the guest result as boolean
   (`boolean` of 0/1 word or true/false).

`better-score?` / pick-idx / assign triples stay `:i64` 0/1 for this slice —
those are ranking codes, not predicates.

## Evidence

- Live KIR regenerated for `infer_schedule_core.kir.edn`
- `:test` compiler pin → profile-5 tip (`b2291313`)
- schedule unit + parity + authority tests green

## Follow-up

- `task-eligible?` and other `*?` planners the same way
- Prefer-warm / better-score chains can stay numeric until a pure-bool API
  is designed for pick codes
