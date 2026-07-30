# ADR-260731: Profile 5 — schedule eligibility fields and predicates are :bool

## Status
Accepted (implementation PR)

## Context
Language profile 5 (compiler#451 / ADR 0191) types comparisons and predicates
as `:bool`. T5.3 already replaced bit-packed eligibility with a record of
`:i64` 0/1 fields. The next step is real booleans on that record and on
`eligible?` / `holds-warm?`.

## Decision
- `:schedule/eligibility` fields: `:has-engine`, `:has-checkpoint`,
  `:holds-checkpoint`, `:can-fetch` are `:bool`.
- `eligible?` and `holds-warm?` return `:bool`.
- Host `murakumo.infer.schedule` builds host booleans into
  `oracle/record` (oracle projects `:bool` via `boolean`).
- Host treats oracle results with `boolean` so both KIR 0/1 words and host
  booleans are truthy correctly.
- `eligible?` is written with nested `if` (not `and`/`or`) so precompiled KIR
  is gensym-stable for the drift gate.
- Pins: `compiler@b2291313`, `kotoba-kir@15e208ea` (accept 0/1 and booleans).

## Consequences
- Product-shell path for schedule eligibility is Clojure-shaped on the guest.
- Remaining: `task/eligibility` and other 0/1 product fields; score/pick paths
  still use numeric 0/1 for multi-node tournament codes.
