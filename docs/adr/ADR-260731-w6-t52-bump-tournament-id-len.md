# ADR-260731: T5.2 native guest record — LR bump tournament + identifier-len

- Status: accepted
- Date: 2026-07-31
- Depends: rebalance pairs residual (ce1cd9d8)
- WBS: T5.2 residual multi-arg pure

## Decision

### plan + rebalance (largest-remainder bump)
| Export | Schema (plan / rebalance) |
|--------|---------------------------|
| `better-bump?` | `:plan/bump-cmp` / `:rebalance/bump-cmp` |
| `ok-for` | `:plan/ok-for` / `:rebalance/ok-for` |
| `pick-bump-3` | `:plan/bump3-in` / `:rebalance/bump3-in` |

Internal `plan-lr-record` / `seats-record` compose via `record-new`.

### component_authority
| `identifier-len-ok?` | `:cauth/id-len` |
Host uses `call-record` `:raw`; `identifier?` guest composes `record-new`.

## Non-claims

- plan partition multi-arg with model ref remains
- digit scanners optional
- T8.3 nested EDN still W4-gated

## Evidence

- KIR regenerated for plan / rebalance / component_authority
- Focused suite 117 tests / 1892 assertions green
