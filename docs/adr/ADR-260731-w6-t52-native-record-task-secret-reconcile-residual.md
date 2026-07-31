# ADR-260731: T5.2 native guest record wire — task / secret / reconcile residual multi-arg

- Status: accepted
- Date: 2026-07-31
- Depends: option-i64 residual + infer-engine residual
- WBS: T5.2 native guest record wire expansion

## Decision

Fold remaining multi-scalar pure inputs (no option-string) into single guest records:

### task_plan_core
| Export | Schema |
|--------|--------|
| `slots` | `:task/slots` |
| `wave-of` / `slot-of` | `:task/wave` |
| `fill-milli` / `better-fill?` / `load-inc-if` / nearest-rank / summary / speedup | `:task/pair` |
| `better-mem?` | `:task/better-mem` |
| `task-score-code` | `:task/score` |
| `challenger-wins?` | `:task/challenger` |
| `pick-task-fold-step` | `:task/pick-fold` (option-i64 champ) |
| `unschedulable-detail` | `:task/unsched` |

### secret_core
| `classify-fetched` | `:secret/fetched` |

### reconcile_plan_core
| `deficit` | `:reconcile/deficit` |

Host: `oracle/record` + `call-record` `:raw`.

## Non-claims

- option-string-in-record still compiler-blocked
- action-name / failed? / parts-present? / sealed / choose-via stay positional

## Evidence

- KIR regenerated for task/secret/reconcile
- 91 tests / 1646 assertions (parity + authority + unit) green
