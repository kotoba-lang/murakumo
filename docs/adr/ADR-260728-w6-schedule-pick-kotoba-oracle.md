# ADR-260728: W6 pure-planner oracle — schedule pick for fixed candidates

Status: accepted after eligible?/score core

## Decision

Extend `infer_schedule_core.kotoba` with pure `pick` for fixed 2/3 candidates:

| export | role |
|---|---|
| `holds-warm?` | flags bit 4 (holds checkpoint) |
| `prefer-warm-then-score` | warm preference then score |
| `pick-idx-2-full` | pure pick between two nodes (−1/0/1) |
| `pick-idx-3-tournament` | champion vs third node |
| `queue-after-assign` | assign queue inc |

Host projects eligibility/warm/score keys; guest applies pure selection.

### Not ported

- `assign` over job batches (atom / vector update)
- variable-length node filter/sort

## Evidence

- `test/murakumo/infer_schedule_kotoba_parity_test.clj` (4 tests / 29 assertions)
