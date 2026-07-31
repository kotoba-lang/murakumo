# ADR-260731: T5.2 native guest record wire — schedule residual multi-arg

- Status: accepted
- Date: 2026-07-31
- Depends: credits residual (9337d82); prior schedule eligibility/assign records
- WBS: T5.2 residual multi-arg pure (schedule score/pick/queue)

## Decision

Fold schedule multi-scalar pure into single guest records:

| Export | Schema |
|--------|--------|
| `better-score?` | `:schedule/score-cmp` |
| `better-from-queues` / `better-pair` | `:schedule/queue-cmp` |
| `prefer-warm-then-score` | `:schedule/better2` (reuse) |
| `pick-idx-2-full` | `:schedule/pick2` |
| `pick-idx-3-tournament` | `:schedule/pick3-tour` |
| `queue-after-assign` / `queue-inc-if` | `:schedule/queue-step` |

Internal assign-step / pick-fold compose via `record-new`. Host `assign`
`queue-inc-if` uses `call-record` `:raw`.

## Non-claims

- `pick-fold-step` still multi-arg + `[:option :i64]` champ (host fold).
- Record constructors (`better2-record`, `triple-record`, …) stay multi-arg.
- Digit scanners optional residual; T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated: `infer_schedule_core.kir.edn`
- Focused schedule parity + authority + call-record: 98 tests / 1715 assertions green
