# ADR: T5.3 (schedule) — assign pack families become named records

- Status: Accepted
- Date: 2026-07-30
- WBS: T5.3 residual after eligibility (#195) and plan residual packs (#198)
- Superproject: ADR-2607299400 P2 / kotoba-lang#345 follow-on

## Context

`infer_schedule_core` still used base-65536 `pack3` for:

- 2-node assign better-context and result (`assign-step-2` → code+q0+q1)
- 3-node queue / ok / warm triples, better-pair triple, and
  `assign-step-3` result encoded as `pick-code + 4 * pack3(q0,q1,q2)`

Eligibility flags were already a record (#195). The assign packs were the
remaining public base-N surface in this module. Production host
(`murakumo.infer.schedule/assign`) uses pick/score/queue-inc only and never
called the pack APIs; the packs lived for pure tournament parity tests and
as a forbidden-pattern residual.

## Decision

Named schemas on the ns form:

| schema | fields | role |
|---|---|---|
| `:schedule/better2` | warm0, warm1, better01 | 2-node assign context |
| `:schedule/assign2` | code, q0, q1 | 2-node assign result |
| `:schedule/triple` | v0, v1, v2 | 3-node queue / ok / warm |
| `:schedule/better3` | b01, b02, b12 | pairwise better flags |
| `:schedule/assign3` | code, q0, q1, q2 | 3-node assign result |

Public constructors: `better2-record`, `triple-record`, `better3-record`.
`lane-base` / `pack3` / `pack-get` / `queues-pack-*` deleted.
`assign-step-3-queues` (integer unpack of the encoded result) replaced by
`assign-step-3-q0` / `q1` / `q2` field projections.

Parity tests project record fields inside the guest (cannot splice records
as integer literals across cases) — same pattern as plan residual packs.

## Evidence

- schedule parity: 8 tests / 70 assertions / 0 failures
- schedule host + parity: 14 tests / 84 assertions / 0 failures
- precompiled KIR regenerated; drift check live == resource

## Non-goals

- Host production path already does not call assign-step packs (no host
  dual-source change required for assign)
- rebalance internal pool-weight packs and credits/task/reconcile packs are
  separate modules / slices
- bool-typed predicates (`eligible?` still returns `:i64` 0/1) deferred to
  the bool-comparison opt-in slice
