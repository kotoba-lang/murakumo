# ADR-260731: T5.2 native guest record — rebalance seat-order residual

- Status: accepted
- Date: 2026-07-31
- Depends: plan ends-idx (f9c6a6c); rebalance pairs residual
- WBS: T5.2 residual multi-arg pure (rebalance seat order)

## Decision

| Export | Schema |
|--------|--------|
| `count-active` | `:rebalance/triple` (a=wt b=wm c=wp) |
| `min2` / `max2` | `:rebalance/pair` |
| `seat-of` | `:rebalance/seat-of` |
| `before-pool?` | `:rebalance/before-pool` |
| `seat-order-record` | `:rebalance/triple` (a=st b=sm c=sp) |
| `order-nth` | `:rebalance/order-nth` (i0/i1/i2 + n) |

`take-end`/`take-count`/`seats-record` compose via `record-new`.

## Non-claims

- Digit-scanner multi-arg internals optional
- T8.3 nested EDN still W4-gated

## Evidence

- KIR regenerated `infer_rebalance_core`
- rebalance parity + authority 80/1511 green
