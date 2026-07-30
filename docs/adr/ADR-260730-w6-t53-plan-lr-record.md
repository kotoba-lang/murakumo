# ADR: T5.3 (plan) — the largest-remainder lane becomes a record

- Status: Accepted
- Date: 2026-07-30
- WBS: T5.3, third and last slice of the largest-remainder pattern

## Context

`infer_plan_core.kotoba` carried its own copy of the base-65536 apportionment
pack — the same shape already removed from `infer_rebalance_core` and
`infer_schedule_core`:

```kotoba
(defn plan-lr-3 [total :i64 w0 :i64 w1 :i64 w2 :i64] :i64
  … (pack3 (+ f0 bump0) (+ f1 bump1) (+ f2 bump2)))
(defn plan-lr-pack-get [packed :i64 idx :i64] :i64 …)
```

`plan-lr-3`, `plan-lr-pack-get` and `lane-base` were all public exports, so the
pack was part of the oracle's API even though `murakumo.infer.plan` calls only
`usable-bytes` and `choose-strategy-name` today. Leaving it means the next
consumer to adopt this lane inherits the pack.

## Decision

- `[:record :plan/lanes [[:l0 :i64] [:l1 :i64] [:l2 :i64]]]` replaces the pack.
- `plan-lr-3` → `plan-lr-record`, returning that record.
- `pick-bump-3` takes the remainders **as a record parameter**, so the internal
  `rem-packed` and the `_pad` argument that only existed to reach a fixed arity
  are both gone.
- Public API is three scalar lane projections: `plan-lr-l0` / `plan-lr-l1` /
  `plan-lr-l2`.
- Unexported: `plan-lr-3`, `plan-lr-pack-get`, `lane-base`.
- `plan-lr-pack-get` is renamed `lane-at`, because it survives as a *generic*
  accessor for the pack families this slice does not touch — naming it after the
  plan-lr lane would now be a lie.

Field projection uses the 2-arity `(record-get rems :l0)` sugar (compiler
ADR 0189 + the #442 pass-order fix).

## Evidence

Differential parity against the pre-change module, both compiled under
`:language-profile :pure-product` and executed on KIR:

| Comparison | Grid | Mismatches |
|---|---|---|
| `plan-lr-l{0,1,2}` vs `plan-lr-pack-get(plan-lr-3 …)` | 1040 | **0** |
| `model-pack` / `model-layers` / `model-dense` / `plan-fits-total?` / `span-fits?` / `uniform-layer-bytes` (must be untouched) | 10 | **0** |

Suites:

- `murakumo.infer-plan-kotoba-parity-test` — 12 tests, 100 assertions, 0 failures
- `murakumo.kotoba-oracle-authority-test` — 66 tests, 1174 assertions, 0 failures
- Full `clojure -M:test` — 12 failures, all `…-over-real-quic` live-transport
  cases. Across five runs on four trees (including pristine `main`) that suite
  has failed 5 / 6 / 3 / 7 / 12 assertions with a **different subset each
  time**, and it touches neither plan, schedule nor rebalance.

`resources/murakumo/oracle/infer_plan_core.kir.edn` regenerated.

## Still packed in this module

Four families remain, each a separate slice:

| Family | Exports |
|---|---|
| model | `model-pack` / `model-layers` / `model-dense` / `model-frac-milli` |
| assignment row | `asg-row-pack` / `asg-row-span` / `asg-row-fits` |
| partition step | `lo-acc-pack` / `partition-step` / `partition-step-hi` / `partition-step-acc` |
| partition ends | `partition-2-ends` / `partition-3-ends` |

`lane-base` / `pack3` / `lane-at` stay internal for them.

## Related

- `docs/adr/ADR-260729-w6-t53-rebalance-seats-record.md`
- `docs/adr/ADR-260730-w6-t53-schedule-eligibility-record.md`
- compiler `docs/adr/0189-record-projection-sugar.md`
- kotoba-lang `ADR-reliability-t51-structural-args`
