# ADR-260728: W6 pure-planner oracle — credits integer + reconcile action

Status: accepted medium-priority cutover slice of `murakumo-pure-planners-v2-medium`

## Decision

### `kotoba/infer_credits_core.kotoba`
Integer core of `murakumo.infer.credits`:

| function | notes |
|---|---|
| `default-per-token` / head+protocol num/den | 1, 1/10, 1/20 |
| `token-cost` / `cut` / `pool` | settle top-line math |
| `memory-time-weight` | span-gated est×duration |
| `charge-allow?` | balance gate |

Float proportional share maps and ledger folds stay cljc.

### `kotoba/reconcile_plan_core.kotoba`
Scalar decision core of `murakumo.reconcile.plan`:

| function | notes |
|---|---|
| `desired` / `deficit` / `watch-sleep-ms` | defaults + math |
| `action-name` | needs-build/over/satisfied/blocked/place |

Node set algebra (`eligible-nodes`, `pick-targets`) stays cljc; host projects counts.

## Evidence

- `test/murakumo/infer_credits_kotoba_parity_test.clj`
- `test/murakumo/reconcile_plan_kotoba_parity_test.clj`

## Related

- murakumo#43–#46 medium pure-planner oracles
