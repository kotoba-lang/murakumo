# ADR-260728: W6 pure-planner oracle — fleet inventory port/selector core

Status: accepted medium-priority cutover slice of `murakumo-pure-planners-v1`

## Decision

Port the integer/string core of `murakumo.fleet.inventory` to
`kotoba/fleet_inventory_core.kotoba`:

| function | notes |
|---|---|
| `default-control-port` | 8077 fallback constant |
| `resolve-port` | node port → fleet port → 8077 (`has-*` sentinels) |
| `health-url` | `http://localhost:<port>/health` |
| `selector-is-all?` | empty/`"all"` → 1 |
| `selector-wants-name?` | exact comma-token membership |
| `line-has-offline?` | tailscale status line offline predicate |

### Not ported

- `select` / `node-named` / `enrich` over node vectors (list/map reduce stays cljc)
- `parse-tailscale-status` column split (host line walk; offline flag is ported)

## Evidence

- `test/murakumo/fleet_inventory_kotoba_parity_test.clj`
- Equality against `murakumo.fleet.inventory` on the offline unit corpus

## Related

- murakumo#37–#42 high-priority pure-planner oracles
- `lang/w6-murakumo-path-inventory.edn` medium cutover candidates
