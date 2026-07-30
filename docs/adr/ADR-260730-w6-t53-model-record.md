# ADR: T5.3 (plan) — the model shape becomes a named record

- Status: Accepted
- Date: 2026-07-30
- WBS: T5.3, fourth lane

## Context

`model-pack` packed `(layers, dense, dense-frac-milli)` into one base-65536 i64
and was then **threaded through 16 signatures** as `mp :i64` —
`layer-byte-at`, `layer-wsum`, `est-bytes-range`, `advance-hi`,
`partition-{1,2,3}-*`, `plan-fits-{1,2,3}`, `asg-row-pack`, `partition-step`,
`partition-last`.

The plan-lr slice (`ADR-260730-w6-t53-plan-lr-record.md`) deliberately stopped
here. Converting `mp` with inline descriptors would have put **16 copies** of
`[:record :plan/model [[:layers :i64] [:dense :i64] [:frac-milli :i64]]]` in one
file — strictly harder to read than the pack. Mechanically applying the T5.3
pattern would have made this module worse.

compiler ADR 0190 removed that obstacle: `[:ref :ns/name]` schema references now
resolve in the record operations, so the descriptor is declared **once** on the
namespace form.

## Decision

```kotoba
(ns infer-plan-core
  (:schemas {:plan/model [:record :plan/model
                          [[:layers :i64] [:dense :i64] [:frac-milli :i64]]]})
  (:export [… model-record model-layers model-dense model-frac-milli …]))

(defn model-record [layers :i64 dense :i64 dense-frac-milli :i64] [:ref :plan/model]
  (record-new [:ref :plan/model] layers dense dense-frac-milli))

(defn model-layers [mp [:ref :plan/model]] :i64 (record-get mp :layers))
```

- `model-pack` → `model-record`, returning `[:ref :plan/model]`
- the three projections take the reference and use the 2-arity `record-get`
- all 13 remaining `mp :i64` annotations become `mp [:ref :plan/model]`
- `model-pack` is gone from the exports

`lane-base` / `pack3` / `lane-at` stay internal for the three lanes still packed.

## Evidence

Differential parity against the pre-change module, both compiled under
`:language-profile :pure-product` and executed on KIR. Model shapes swept over
layers ∈ {0,1,4,12,33} × dense ∈ {0,1,7} × frac-milli ∈ {0,100,999}:

| Export | Cases | Mismatches |
|---|---|---|
| `model-layers` | 45 | **0** |
| `model-dense` | 45 | **0** |
| `model-frac-milli` | 45 | **0** |
| `layer-wsum` | 180 | **0** |
| `est-bytes-range` | 720 | **0** |
| `partition-3-ends` | 360 | **0** |
| `plan-fits-3` | 360 | **0** |
| `asg-row-pack` | 180 | **0** |
| `partition-last` | 45 | **0** |

**1,980 comparisons, 0 mismatches.**

Suites:

- `murakumo.infer-plan-kotoba-parity-test` — 12 tests, 100 assertions, 0 failures
- `murakumo.kotoba-oracle-authority-test` — 66 tests, 1174 assertions, 0 failures
- Full `clojure -M:test` — 539 tests, 3 failures, all
  `ledger-quorum-fn-reaches-witnessed-over-real-quic`. That live-transport suite
  has now failed 5 / 6 / 3 / 7 / 12 / 3 assertions across six runs on five trees
  (including pristine `main`), a different subset each time.

KIR regenerated. `:test` compiler pin advances to `f22f7f35` for ADR 0190.

## Still packed in this module

| Family | Exports |
|---|---|
| assignment row | `asg-row-pack` / `asg-row-span` / `asg-row-fits` |
| partition step | `lo-acc-pack` / `partition-step` / `partition-step-hi` / `partition-step-acc` |
| partition ends | `partition-2-ends` / `partition-3-ends` |

All three are *result* packs of 2–3 small integers, and none is threaded the way
`mp` was — so each is a straightforward lane-projection slice like plan-lr.

## Related

- compiler `docs/adr/0190-record-schema-references.md` (the enabler)
- `docs/adr/ADR-260730-w6-t53-plan-lr-record.md`
- `docs/adr/ADR-260730-w6-t53-schedule-eligibility-record.md`
- `docs/adr/ADR-260729-w6-t53-rebalance-seats-record.md`
