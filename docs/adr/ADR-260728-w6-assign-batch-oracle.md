# ADR-260728: W6 pure-planner oracle — schedule assign-step + task eligible/score

Status: accepted after schedule pick (#73) + reconcile pick-targets (#75)

## Decision

### `infer_schedule_core` — assign job batch step

| export | role |
|---|---|
| `pack3` / `pack-get` / `queues-pack-2` | small-int packs (queues) |
| `pick-code` | −1/0/1 → 0/1/2 |
| `assign-step-2` | one job pure assign for 2 nodes → pick-code + new queues |
| `better-from-queues` | recompute score after queue updates |
| getters | unpack assign-step result |

Host loops jobs, projecting eligibility/warm each step. Variable-arity node
filter/sort stays cljc.

### `task_plan_core` — assign-1 pure core

| export | role |
|---|---|
| `task-eligible?` | bit flags + mem gate (host projects set/label algebra) |
| `fill-milli` / `better-task-score?` / `better-mem?` | least-loaded score |
| `wave-of` / `slot-of` / `load-after-assign` | assignment metadata |

Flags: 1 online \| 2 labels \| 4 roles \| 8 not-excluded \| 16 allowlist.

## Evidence

- `test/murakumo/infer_schedule_kotoba_parity_test.clj`
- `test/murakumo/task_plan_kotoba_parity_test.clj`
- Combined: 12 tests / 94 assertions

## Related

- inventory Next: assign job batches / eligible-nodes set algebra
- murakumo#73 schedule pick
