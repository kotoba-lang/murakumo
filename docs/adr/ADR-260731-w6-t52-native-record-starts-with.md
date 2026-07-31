# ADR-260731: T5.2 native guest record wire — starts-with + dial-reason

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo native-record waves through infer-engine residual
- WBS: T5.2 native guest record wire expansion

## Decision

Fold remaining multi-scalar pure scanners/classifiers into single guest records:

| Module | Export | Schema | Fields |
|--------|--------|--------|--------|
| `overlay_driver_core` | `starts-with?` | `:driver/starts-with` | s, prefix |
| `overlay_driver_core` | `dial-ok-reason` | `:driver/dial-reason` | is-dial, missing-n |
| `overlay_runtime_core` | `starts-with?` | `:runtime/starts-with` | s, prefix |
| `reconcile_plan_core` | `starts-with?` | `:reconcile/starts-with` | s, prefix |

Internal call sites use `record-new` (endpoint-kind, option-name, flag-is-*,
watch-seconds, snapshot-value). Host builds `oracle/record` + `call-record`
`:raw` for `dial-ok-reason`.

## Non-claims

- Single-arg residual stay scalar.
- Digit scanners (`digit-of-go`, `find-scheme-end`, …) stay multi-arg internal.
- `[:option :string]` in-record still a compiler gap (measured earlier).
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for overlay_driver / overlay_runtime / reconcile_plan only
- Focused parity + authority green
