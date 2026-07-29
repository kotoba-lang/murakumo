# ADR: T5.3 — rebalance seats become a record, the base-65536 seat pack is deleted

- Status: Accepted
- Date: 2026-07-29
- WBS: T5.3 (depends on T5.1 / T4.4 / T5.2)

## Context

`kotoba/infer_rebalance_core.kotoba` returned the three-pool apportionment as a
single i64 with base-65536 digits:

```kotoba
;;   packed = text + media*B + postproc*B*B, B=65536
(defn largest-remainder-3 [total :i64 wt :i64 wm :i64 wp :i64 floor :i64] :i64 …)
(defn seats-text [packed :i64] :i64 (rem64 packed (lane-base)))
```

The host then made four oracle calls: one to build the pack and three to take it
apart. `lang/pure-product-profile.edn` `:forbidden-patterns` has listed
*"base-65536 public packs when a small record/hetero-vector export is
available"* since T5.1, but nothing had deleted an existing one.

The pack existed because of `max-parameters 5` (T5.4 keeps it), not because the
language lacked records. Two probes against `kotoba-lang/compiler@45f0e5e3`
confirm the guest surface is there today:

| Probe | Result |
|---|---|
| `record-new` returned from a `:pure-product` export, run on KIR | `[[:record :r/s [[:text :i64] …]] 3 4 5]` |
| `[:record …]` as a **function parameter** | `OK` |

## Decision

### Guest

- `[:record :rebalance/lanes [[:text :i64] [:media :i64] [:postproc :i64]]]`
  carries both the apportioned seats and the largest-remainder remainders.
- `largest-remainder-3` → `seats-record`, returning that record.
- `pick-bump-3` takes the remainders **as a record parameter**, so the internal
  `rem-packed` pack and the `_pad` argument that only existed to reach the old
  fixed arity are both gone.
- Public API is three scalar lane projections plus a total:
  `seats-of-text` / `seats-of-media` / `seats-of-postproc` / `seats-total`.
  Same for the composed forms: `pool-seats-of-*` (was `seats-from-pool-pack`)
  and `seats-for-online-*` (was `seats-for-online`).
- Removed from `:export`: `largest-remainder-3`, `seats-text`, `seats-media`,
  `seats-postproc`, `lane-base`, `seats-from-pool-pack`, `seats-for-online`,
  `assigned-from-seats`.

### Host

`murakumo.infer.rebalance/largest-remainder` now makes **three** calls instead
of one pack call plus three unpack calls. No pack crosses the boundary.

### Deliberately out of scope

`lane-base` / `pack3` / `lane-0..2` survive as **internal** helpers for the
*pool-weight* pack (`pool-demand-pack` / `demand-to-pool-pack`) and the
base-4096 *demand* pack. Those are different lanes and a separate slice; the
unpackers were renamed from `seats-*` to `lane-*` so nothing reads as a seat
pack any more. `infer_plan_core.kotoba` has its own `plan-lr-3` copy of the old
pattern — also a separate slice.

## Evidence

Differential parity against the pre-change module, both compiled under
`:language-profile :pure-product` and executed on KIR:

| Comparison | Grid | Mismatches |
|---|---|---|
| `seats-of-{text,media,postproc}` vs `seats-{text,media,postproc}(largest-remainder-3 …)` | 3360 | **0** |
| `seats-total` vs `assigned-from-seats(largest-remainder-3 …)` | 3360 | **0** |
| `pool-seats-of-*` vs `seats-*(seats-from-pool-pack …)` | 1728 | **0** |

Suites:

- `murakumo.infer-rebalance-kotoba-parity-test` — 10 tests, 92 assertions, 0 failures
- `murakumo.kotoba-oracle-authority-test` — 66 tests, 1174 assertions, 0 failures
- `murakumo.infer-rebalance-test` — 8 tests, 27 assertions, 0 failures
- Full `clojure -M:test` — 538 tests; the only failures are the live-QUIC
  `overlay_witness_write_test` / `overlay_quic_driver_live_test` cases, which
  fail on the pristine baseline too and pick a **different** subset each run
  (baseline 5, with-change 6, different test names). Flaky live transport, not
  this change.

`resources/murakumo/oracle/infer_rebalance_core.kir.edn` regenerated via
`murakumo.kotoba-oracle-gen/write-artifact!`.

## Related

- kotoba-lang `ADR-reliability-t51-structural-args`, `ADR-reliability-t54-max-parameters`
- `docs/lang/record-cookbook.md` (T4.4)
- `docs/adr/ADR-260728-w6-t52-oracle-call-record.md` (T5.2 inbound bridge)
- superproject `ADR-2607299400`
