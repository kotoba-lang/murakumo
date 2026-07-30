# ADR: T5.3 (plan) — the last three pack families; no packing left in the module

- Status: Accepted
- Date: 2026-07-30
- WBS: T5.3, final plan lane

## Context

After the plan-lr and model lanes, `infer_plan_core.kotoba` still had three
base-65536 families, all listed as follow-ups in the two previous ADRs:

| Family | Exports |
|---|---|
| assignment row | `asg-row-pack` / `asg-row-span` / `asg-row-fits` |
| partition fold state | `lo-acc-pack` / `partition-step` / `partition-step-hi` / `partition-step-acc` |
| partition ends | `partition-2-ends` / `partition-3-ends` (+ `ends-lo` / `ends-hi`) |

## Decision

Three more named schemas on the namespace form, and the packs are gone:

```kotoba
:plan/row  [:record :plan/row  [[:span :i64] [:fits :i64]]]
:plan/cut  [:record :plan/cut  [[:at :i64] [:acc :i64]]]
:plan/ends [:record :plan/ends [[:hi0 :i64] [:hi1 :i64] [:hi2 :i64]]]
```

- `asg-row-pack` → `asg-row-record` : `[:ref :plan/row]`; the two projections
  take the reference.
- `lo-acc-pack` → `cut-state` : `[:ref :plan/cut]`. `partition-step` now takes
  **and returns** `[:ref :plan/cut]`. The field is named `:at`, not `lo`/`hi`,
  because it is the layer boundary — the previous cut on the way in, the new cut
  on the way out. One record serves both directions of the host fold, which the
  pack could only do by coincidence of both being an integer.
- `partition-{2,3}-ends` → `[:ref :plan/ends]`. `ends-lo` / `ends-hi` take the
  reference; a new `ends-at` holds the index if-chain, since record fields are
  literal keywords and the index is a runtime value.
- **`lane-base`, `pack3` and `lane-at` are deleted** — nothing in the module
  packs any more. `grep -c '65536'` over the module and over its parity test are
  both **0**.

## Evidence

Differential parity against the pre-change module, both compiled under
`:language-profile :pure-product` and executed on KIR. Model shapes swept over
layers ∈ {0,1,4,12,33} × dense ∈ {0,1,7} × frac-milli ∈ {0,100,999}:

| Comparison | Cases | Mismatches |
|---|---|---|
| `asg-row` span + fits | 1440 | **0** |
| `partition-step` hi + acc, via the fold state | 2880 | **0** |
| `partition-2-ends` + `partition-3-ends` through `ends-lo` / `ends-hi` | 360 | **0** |
| `plan-fits-2` + `plan-fits-3` (consume ends internally, must be unchanged) | 360 | **0** |

**5,040 comparisons, 0 mismatches.**

The parity test needed restructuring rather than renaming: it used to unpack
results with host-side `mod`/`quot` and thread integers between fold steps.
Records cannot be spliced into generated source as integer literals, so ends are
now projected inside the guest (`ends-at`) and the fold is a nested expression —
which is closer to how a host actually uses it. `unpack3` is deleted.

- `murakumo.infer-plan-kotoba-parity-test` — 12 tests, 98 assertions, 0 failures
- `murakumo.kotoba-oracle-authority-test` — 66 tests, 1174 assertions, 0 failures
- Full `clojure -M:test` — 539 tests, 4 failures, all
  `live-rpc-round-trip-over-real-quic`. That live-transport suite has now failed
  5 / 6 / 3 / 7 / 12 / 3 / 4 assertions across seven runs on six trees
  (including pristine `main`), a different subset each time.

KIR regenerated.

## T5.3 is complete

| Module | Pack removed | Comparisons |
|---|---|---|
| `infer_rebalance_core` | seats (base-65536) | 8,448 |
| `infer_schedule_core` | eligibility (4-bit flags) | 336 |
| `infer_plan_core` | largest-remainder (base-65536) | 1,050 |
| `infer_plan_core` | model, threaded through 16 signatures | 1,980 |
| `infer_plan_core` | assignment row / fold state / ends | 5,040 |

**16,854 differential comparisons, 0 mismatches.** No base-N packing remains in
any murakumo pure-planner oracle.

## What is still not beautiful

Every one of these still returns and stores `:i64` where a boolean is meant —
`asg-row-fits`, `plan-fits-*`, `fits-and`, `span-fits?`. Comparisons are
i64-typed, so a predicate cannot be written with `and` / `or` / `not`. That is
the `compiler` + `kotoba-kir` + `kotoba-wasm` slice measured in kotoba-lang's
`ADR-reliability-record-access-and-bool-comparisons`, and it is the next real
step for this code.

## Related

- `docs/adr/ADR-260730-w6-t53-model-record.md`
- `docs/adr/ADR-260730-w6-t53-plan-lr-record.md`
- compiler `docs/adr/0190-record-schema-references.md`
