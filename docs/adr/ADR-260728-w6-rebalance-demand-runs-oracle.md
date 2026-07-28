# ADR-260728: W6 rebalance demand-from-runs map-fold oracle

- Status: Accepted
- Date: 2026-07-28

## Context

#61/#63 landed largest-remainder, pool-demand-pack, and classify-run-flags.
`demand-from-runs` (class-count reduce over the run ledger) was still cljc.

## Decision

Extend `infer_rebalance_core.kotoba`:

| function | notes |
|---|---|
| `demand-empty` / `demand-inc` | class-count pack reduce (base 4096, 5 lanes) |
| `demand-text`…`demand-postproc` | unpack lanes |
| `demand-to-pool-pack` | compose → existing pool-demand-pack |

Host projects unit/kind presence flags; guest owns classify + demand-inc fold.

### Still cljc

- capacity vector filter / node-capacity maps
- target-allocation placement moves
- rebalance hysteresis / moves-between

## Evidence

- `test/murakumo/infer_rebalance_kotoba_parity_test.clj`
