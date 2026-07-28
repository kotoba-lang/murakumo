# ADR-260728: W6 pure-planner oracle — rebalance pool-demand + classify map-fold

Status: accepted second map-fold product vertical after largest-remainder-3

## Decision

Extend `infer_rebalance_core.kotoba` with:

| export | meaning |
|---|---|
| `pool-demand-pack` | class counts (text/image/video/audio/postproc) → packed pool weights |
| `seats-from-pool-pack` | `total` + packed weights + floor → seat pack (compose `largest-remainder-3`) |
| `classify-run-flags` | one-run class tag (images>video>audio>swarm>tokens) for demand-from-runs |

Packing reuses base-65536 digits (`text + media*B + postproc*B²`).

### Not ported

- full `demand-from-runs` reduce over run vectors
- `target-allocation` / `moves-between` / `rebalance` placement
- string/kind parsing of run maps (flags projected by host)

## Evidence

- `test/murakumo/infer_rebalance_kotoba_parity_test.clj`

## Related

- murakumo#61 largest-remainder-3
- inventory: map folds beyond scalar oracles
