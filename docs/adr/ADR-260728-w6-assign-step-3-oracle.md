# ADR-260728: W6 pure-planner oracle — schedule assign-step-3

Status: accepted after assign-step-2 (#76) + n≠3 plan maps (#77)

## Decision

Extend `infer_schedule_core.kotoba` with a pure assign batch step for **fixed
3 nodes** (variable n>3 still cljc):

| export | role |
|---|---|
| `queues-pack-3` / `pick-code-3` | pack queues; −1..2 → 0..3 |
| `better-pair` | pairwise better-from-queues alias for host pack |
| `assign-pick-3` | tournament pick via pick-idx-2 + pick-idx-3-tournament |
| `apply-pick-3` | pack3 new queues after pick |
| `assign-step-3` | one job → `code + 4 * pack3(nq0,nq1,nq2)` |
| `assign-step-3-code` / `assign-step-3-queues` | unpack result |

Host projects ok/warm flags and pairwise better-pack
`pack3(better01, better02, better12)` each step (free-bytes stay host-side).

## Evidence

- `test/murakumo/infer_schedule_kotoba_parity_test.clj` (`assign-step-3-matches-schedule-assign`)

## Related

- inventory Next: full assign reduce over variable nodes
- murakumo#73 schedule pick, #76 assign-step-2
