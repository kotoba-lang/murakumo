# ADR-260728: W6 pure-planner oracle — dash state display core

Status: accepted third cutover slice of `murakumo-pure-planners-v1`

## Decision

Port the string/integer display helpers of `murakumo.dash.state` to
`kotoba/dash_state_core.kotoba`:

| function | notes |
|---|---|
| `short-hosted-cid` | ASCII substring 0..min(18, len) |
| `health-class-of` | `"ok"` → `"ok"`, else `"down"` |
| `interval-sleep-ms` | seconds × 1000 |
| `clamp-at` | history offset clamp (nil → 0 at call site) |

### Not ported

- `placements` / `links-total` / `snapshot-record` (vector reduce)
- `selected-snapshot` / `recent-alerts` / `append-capped` (collections)
- `render-html` / query parsing / probe parsers (host display shell)

## Evidence

- `test/murakumo/dash_state_kotoba_parity_test.clj`
- Equality against `murakumo.dash.state` offline unit corpus

## Related

- murakumo#37 kekkai gate oracle
- murakumo#38 infer plan oracle
- `lang/w6-murakumo-path-inventory.edn` first-cutover-slice
