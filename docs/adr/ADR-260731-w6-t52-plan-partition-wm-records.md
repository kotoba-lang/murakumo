# ADR-260731: T5.2 native guest record — plan partition weight+model surface

- Status: accepted
- Date: 2026-07-31
- Depends: LR bump tournament residual (a6ba3ccd)
- WBS: T5.2 residual multi-arg pure (plan partition)

## Decision

Fold weight+model (+ usable / range / cut) multi-arg pure on `infer_plan_core`
into single guest records:

| Export | Schema |
|--------|--------|
| `uniform-layer-bytes` | `:plan/ul` |
| `dense-units-milli` | `:plan/dense-units` |
| `moe-layer-bytes` / `dense-layer-bytes` / `layer-wsum` | `:plan/wm` |
| `layer-byte-at` | `:plan/wm-i` |
| `est-bytes-range` | `:plan/wm-range` |
| `advance-in-band` | `:plan/band` |
| `advance-hi` | `:plan/wm-adv` |
| `partition-target` / `min2` / `max2` | `:plan/triple` / `:plan/pair` |
| `partition-2-ends` / `plan-fits-2` | `:plan/wm-u2` |
| `partition-3-ends` / `plan-fits-3` | `:plan/wm-u3` |
| `plan-fits-1` | `:plan/wm-u1` |
| `asg-row-record` | `:plan/wm-asg` |
| `partition-step` | `:plan/wm-step` |
| `cut-state` | `:plan/pair` (a=at b=acc) |

Host `partition-layers` remains host pure; oracle parity exercises guest records.

## Non-claims

- `ends-lo`/`ends-hi` still ends+idx multi-arg
- Digit scanners optional residual
- T8.3 nested EDN still W4-gated

## Evidence

- KIR regenerated `infer_plan_core`
- plan parity 12/98 + authority/call-record green
