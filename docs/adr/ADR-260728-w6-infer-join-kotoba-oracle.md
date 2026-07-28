# ADR-260728: W6 pure-planner oracle — infer join tier core

Status: accepted medium-priority cutover slice of `murakumo-pure-planners-v2-medium`

## Decision

Port the tier/eligibility integer core of `murakumo.infer.join` to
`kotoba/infer_join_core.kotoba`:

| function | notes |
|---|---|
| `max-resident-bytes` | browser 2GiB / wasm 4GiB / native 13GiB |
| `needs-relay?` | browser/wasm always; native uses inbound flag |
| `can?` | work-kind string vs tier capability set |
| `clamp-resident` | enrollment min(mem, tier-max) |
| `eligible-for-work?` | can-kind × residency fit |

### Not ported

- `enrollment` / `partition-work` map+vector assembly (stays cljc)
- tier keyword tables as data (encoded as tier codes 0/1/2)

## Evidence

- `test/murakumo/infer_join_kotoba_parity_test.clj`

## Related

- murakumo#43–#44 medium oracles
- `lang/w6-murakumo-path-inventory.edn` medium-cutover-slice
