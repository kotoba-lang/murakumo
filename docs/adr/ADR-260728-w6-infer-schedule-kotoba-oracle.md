# ADR-260728: W6 pure-planner oracle — infer schedule eligible/score

Status: accepted medium-priority cutover slice of `murakumo-pure-planners-v1`

## Decision

Port the eligibility and score core of `murakumo.infer.schedule` to
`kotoba/infer_schedule_core.kotoba`:

| function | notes |
|---|---|
| `eligible?` | bit-packed flags + free/min → 0/1 (ABI arity ≤5) |
| `score-queue` / `score-free` | score tuple keys as i64 |
| `better-score?` | lexicographic lower-is-better compare |

Host computes set membership (`has-engine`, `holds-checkpoint`); guest applies
the pure and/or/memory rule so the same logic can run in WASM without sets.

### Not ported

- `pick` / `assign` (vector filter/sort/atom queue updates stay cljc)

## Evidence

- `test/murakumo/infer_schedule_kotoba_parity_test.clj`
- Equality against `murakumo.infer.schedule` offline corpus

## Related

- murakumo#43 fleet inventory oracle (medium)
- murakumo#37–#42 high-priority pure-planner oracles
