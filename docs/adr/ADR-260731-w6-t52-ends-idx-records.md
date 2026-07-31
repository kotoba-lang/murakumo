# ADR-260731: T5.2 native guest record — plan ends+idx surface

- Status: accepted
- Date: 2026-08-01
- Depends: plan partition weight+model records (eae17fc3)
- WBS: T5.2 residual multi-arg pure (ends-lo/hi)

## Decision

Fold `ends-at` / `ends-lo` / `ends-hi` multi-arg pure (`[:ref :plan/ends]` + `idx`)
into a single guest record:

| Export | Schema |
|--------|--------|
| `ends-at` / `ends-lo` / `ends-hi` | `:plan/ends-idx` (`hi0`/`hi1`/`hi2`/`idx`) |

Host/parity packs an ends projection + idx via `record-new` of flat hi fields
(same flatten pattern as `:plan/wm-i`).

## Non-claims

- Digit scanners (`parse-digits-go` etc.) remain optional residual multi-arg
- T8.3 nested EDN still W4-gated

## Evidence

- KIR regenerated `infer_plan_core`
- plan parity + authority green
