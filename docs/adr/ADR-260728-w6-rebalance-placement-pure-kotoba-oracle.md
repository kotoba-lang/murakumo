# ADR-260728: W6 pure-planner oracle — rebalance placement pure layer

Status: accepted placement math oracle after demand map-folds

## Decision

Extend `infer_rebalance_core.kotoba` with pure placement / hysteresis helpers
used by `target-allocation` and `rebalance` without node-id vectors:

| export | role |
|---|---|
| `workers-count` | online → workers after reserving head |
| `seats-for-online` | online + pool-pack + floor → seat pack |
| `seats-equal` | hysteresis compare of seat packs |
| `pipeline-effective-gb` | usable-gb × text seats |
| `node-online?` | capacity filter (`"up"`) |
| `move-needed` | one-node from/to pool codes |
| `assigned-from-seats` | sum of three pool seats |
| `rebalance-reason-code` / `rebalance-reason-name` | stable / initial / demand-shift |

### Not ported

- head selection among concrete node ids / relay preference
- assigning ids into pool vectors (`take`/`drop` ordered seats)
- full `moves-between` id maps
- cljc `rebalance` string reasons with move counts embedded

## Evidence

- `test/murakumo/infer_rebalance_kotoba_parity_test.clj` (9 tests / 69 assertions)

## Related

- murakumo#61–#64 demand/pool/LR map-folds
