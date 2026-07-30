# ADR-260731: schedule/task ok·warm flags3 are profile-5 :bool

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#227 (ok flags on pick helpers), #224 (better2/3 bool)

## Decision

Close the residual left by `ADR-260731-w6-ok-flags-bool`: multi-role
**`:schedule/triple` / `:task/triple`** mixed queue counts with 0/1 ok·warm
lanes. Split ok·warm into dedicated **`:bool` records**:

| schema | fields | role |
|---|---|---|
| `:schedule/flags3` | `v0/v1/v2 :bool` | ok and warm for 3-node pick |
| `:task/flags3` | `v0/v1/v2 :bool` | ok for 3-node task pick |
| `:schedule/triple` / `:task/triple` | `v0/v1/v2 :i64` | **queues / fill / load only** |

API:

- `flags3-record` (new export) on both cores
- `assign-pick-3` / `assign-step-3` take `ok` + `warm` as `:schedule/flags3`
- `assign-task-pick-3` / `assign-task-step-3` take `ok` as `:task/flags3`
- Drop `(if (= v 1) true false)` projection at the pick boundary

## Still numeric by design

- Queue / load / fill triples and pick codes
- `task-score-code` ternary ordinal
- plan/rebalance bump indices

## Evidence

- KIR regenerated for schedule + task
- Focused parity suite green
