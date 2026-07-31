# ADR-260731: T5.2 native guest record — assign-step-2 + reconcile tournament

- Status: accepted
- Date: 2026-07-31
- Depends: schedule residual / plan residual (5de04485)
- WBS: T5.2 residual multi-arg pure

## Decision

### schedule
| Export | Schema |
|--------|--------|
| `assign-step-2` | `:schedule/assign2-in` (q0/q1/ok0/ok1/warm0/warm1/better01) |

### reconcile
| Export | Schema |
|--------|--------|
| `better-target?` | `:reconcile/better-in` |
| `first-of-2` | `:reconcile/first2` |
| `first-of-3` / `pick-targets-3-first` | `:reconcile/first3` |
| `pick-targets-2-record` | `:reconcile/pick2-in` |

Host `pick-targets` fold stays host pure.

## Non-claims

- Digit scanners optional residual
- plan-fits-1/2/3 + layer partition multi-arg residual remain
- T8.3 nested EDN still W4-gated

## Evidence

- KIR regenerated for schedule + reconcile
- Focused suite 103 tests / 1775 assertions green
