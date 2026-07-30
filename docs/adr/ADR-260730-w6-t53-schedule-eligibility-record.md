# ADR: T5.3 (schedule) — eligibility becomes a record, the flag bit-word is deleted

- Status: Accepted
- Date: 2026-07-30
- WBS: T5.3, and it closes the T5.2 remainder for the KIR path

## Context

`infer_schedule_core/eligible?` packed four booleans into one i64:

```kotoba
;; Guest ABI max arity 5: eligibility flags bit-packed into one i64.
;;   flags bits: 1 has-engine | 2 has-checkpoint | 4 holds-checkpoint | 8 can-fetch
(defn bit? [flags :i64 mask :i64] :i64
  (if (= 0 (rem64 (quot flags mask) 2)) 0 1))

(defn eligible? [flags :i64 free-bytes :i64 min-free :i64] :i64
  (let [has-engine (bit? flags 1) has-ckpt (bit? flags 2) …
```

Four flags plus `free-bytes` plus `min-free` is six values, and
`max-parameters` is 5 (T5.4 keeps it) — so the packing was forced, not chosen.
The rebalance slice (`ADR-260729-w6-t53-rebalance-seats-record`) left this one
open, and `docs/lang/record-cookbook.md` listed it as
*"schedule `eligible?` bit-pack — intentional residual until T5.3 rewrite"*.

Unpacking it needs the **host** to hand a record to the guest. T5.2's
`call-record` ADR recorded the opposite: *"does not yet pass a native guest
`[:record …]` wire value — that needs a KIR record-arg pilot"*. That was the
blocker on paper, so it was measured rather than assumed.

## Measurement (2026-07-29, compiler @ ADR 0189 + fix)

```
guest-built record value: [[:record :s/elig [[:has-engine :i64] …]] 1 1 0 1]
host passes the returned record value      => 1
host passes a hand-built vector           => 1
host passes a bare i64 (wrong)            => REJECT  value is not the declared record type
```

A record crosses the KIR wire **in both directions**, and a wrong shape fails
closed. T5.2's non-claim is out of date for the KIR path.

## Decision

### Guest

`[:record :schedule/eligibility [[:has-engine :i64] [:has-checkpoint :i64]
[:holds-checkpoint :i64] [:can-fetch :i64]]]` replaces the flag word.
`eligible?` takes that record plus `free-bytes` and `min-free` — **arity 3**,
nothing packed. `holds-warm?` takes the record. `bit?` is deleted.

Field projection uses the 2-arity `(record-get e :has-engine)` sugar
(compiler ADR 0189).

### Host

`murakumo.kotoba.oracle/record` builds a native guest record argument from a
schema descriptor and a host map: descriptor followed by field values in
declared order, `:i64` / `:string` projected through the existing helpers, a
missing field throwing rather than defaulting. `call-record`'s stale non-claim
now points at it.

`murakumo.infer.schedule/eligibility-flags` (bit composition) becomes
`eligibility-fields` (a map), passed through `oracle/record`.

## Evidence

Differential parity against the pre-change module, both compiled under
`:language-profile :pure-product` and executed on KIR:

| Comparison | Grid | Mismatches |
|---|---|---|
| `eligible?` — 4 flags × free-bytes × min-free | 320 | **0** |
| `holds-warm?` — all flag combinations | 16 | **0** |

Suites:

- `murakumo.infer-schedule-kotoba-parity-test` — 8 tests, 70 assertions, 0 failures
- `murakumo.kotoba-oracle-authority-test` — 66 tests, 1174 assertions, 0 failures
- `murakumo.infer-schedule-test` — 6 tests, 14 assertions, 0 failures
- `murakumo.oracle-call-record-test` — 3 tests, 11 assertions, 0 failures
- Full `clojure -M:test` — 539 tests, 7 failures, all `…-over-real-quic`
  live-transport cases. Those pick a different subset every run (5 / 6 / 3 / 7
  across four runs on three different trees, including pristine `main`) and
  touch neither schedule nor rebalance.

`resources/murakumo/oracle/infer_schedule_core.kir.edn` regenerated. The
`:test` compiler pin advances to `1ad763fb`, which carries ADR 0189 and the
pass-ordering fix the projection sugar needs.

## Not in this slice

`infer_plan_core.kotoba` still has five pack families (`plan-lr-3` /
`plan-lr-pack-get`, `model-pack`, `asg-row-pack`, `lo-acc-pack` /
`partition-step`, `partition-2-ends` / `partition-3-ends`). One lane per slice,
same as rebalance and schedule.

`eligible?` still returns `:i64` rather than `:bool`, and the flag fields are
`:i64` rather than `:bool`, because comparisons are i64-typed — that is the
`compiler` + `kotoba-kir` + `kotoba-wasm` slice in kotoba-lang's
`ADR-reliability-record-access-and-bool-comparisons`. Turning these into real
predicates (`and` / `or` / `not`) waits on it.

## Related

- `docs/adr/ADR-260729-w6-t53-rebalance-seats-record.md`
- `docs/adr/ADR-260728-w6-t52-oracle-call-record.md` (non-claim corrected here)
- compiler `docs/adr/0189-record-projection-sugar.md`
- kotoba-lang `ADR-reliability-t51-structural-args`, `ADR-reliability-t54-max-parameters`
