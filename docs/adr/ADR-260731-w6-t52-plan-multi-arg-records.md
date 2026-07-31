# ADR-260731: T5.2 native guest record — plan multi-arg residual

- Status: accepted
- Date: 2026-07-31
- Depends: Progress 31er task tournament; schedule residual
- WBS: T5.2 residual multi-arg pure (infer plan)

## Decision

Fold remaining multi-scalar pure exports on `infer_plan_core` into single
guest records:

| Export | Schema | Fields |
|--------|--------|--------|
| `plan-lr-record` / `plan-lr-l0/1/2` | `:plan/weights3` | total, w0, w1, w2 |
| `plan-fits-total?` / `span-fits?` / `assignment-span` / `fits-and` / `layers-range-str` / `pick-max-idx-2` | `:plan/pair` | a, b |
| `pick-max-idx-3` | `:plan/triple` | a, b, c |

Internal `plan-fits-1/2/3` and `asg-row-record` compose via `record-new`.

## Non-claims

- Digit-scanner multi-arg internals remain.
- Larger multi-arg partition helpers (`plan-fits-3` itself still multi-arg with model ref) remain follow-up.
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for `infer_plan_core`
- Focused plan parity + authority: 82 tests / 1518 assertions green
