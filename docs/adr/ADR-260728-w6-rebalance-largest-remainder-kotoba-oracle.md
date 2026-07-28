# ADR-260728: W6 pure-planner oracle — rebalance largest-remainder (3-pool map-fold)

Status: accepted map-fold product vertical slice after scalar rebalance oracle

## Decision

Extend `infer_rebalance_core.kotoba` beyond capacity scalars with the **fixed
3-pool largest-remainder** apportionment used by `murakumo.infer.rebalance`
(`:text-pool` / `:media-pool` / `:postproc-pool`).

Integer form matches cljc double largest-remainder for non-negative weights
(`floor(left * w / sumw)` + modular remainder ranking). Remainder ties break
in pool order text < media < postproc (array-map insertion order).

Packed `i64`: `text | (media << 16) | (postproc << 32)`.

### Not ported

- `demand-from-runs` / `pool-demand` reduce over run vectors
- `target-allocation` node placement / hysteresis moves
- Variable-key weight maps beyond the three fleet pools
- Custom floor maps

## Evidence

- `test/murakumo/infer_rebalance_kotoba_parity_test.clj` (usable-gb + pool-for-class + largest-remainder-3)

## Related

- murakumo#49 optional pure rebalance scalars
- inventory: optional product shells / map folds beyond scalar oracles
