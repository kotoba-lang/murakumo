# ADR-260728: W6 pure-planner oracle — infer gc policy math

Status: accepted medium-priority cutover slice of `murakumo-pure-planners-v2-medium`

## Decision

Port the policy constants and scalar math of `murakumo.infer.gc` to
`kotoba/infer_gc_core.kotoba`:

| function | notes |
|---|---|
| `gib` / `default-target-free` / keep defaults | exact i64 |
| `need-bytes` / `free-after` / `target-met?` | plan field math |
| `rank-better?` | eviction order compare |
| `comfy-evictable?` | atime > keep-days |

### Not ported

- `plan` filter/sort/reduce over entry vectors (stays cljc)
- HF LRU drop/keep list assembly

## Evidence

- `test/murakumo/infer_gc_kotoba_parity_test.clj`

## Related

- murakumo#43–#44 medium oracles
- `lang/w6-murakumo-path-inventory.edn` medium-cutover-slice
