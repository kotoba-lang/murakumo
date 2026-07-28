# ADR-260728: W6 pure-planner oracle — n>3 partition/assign host-fold

Status: accepted after plan n≠3 maps (#77) + assign-step-3 (#79)

## Decision

Port **host-fold** primitives so n>3 rings/batches stay pure without packing
arbitrarily many values into guest ABI:

### `infer_plan_core` — partition reduce

| export | role |
|---|---|
| `lo-acc-pack` | pack3(lo, acc, 0) fold state |
| `partition-step` | non-last node cut → pack3(hi, new-acc, 0) |
| `partition-step-hi` / `partition-step-acc` | unpack step |
| `partition-last` | last exclusive end = layers |
| `fits-and` | fold positive-span fits |

Host walks nodes with cumulative usable; attaches node ids to asg-row-pack.

### `infer_schedule_core` — assign pick reduce

| export | role |
|---|---|
| `pick-fold-step` | 0=none / 1=take challenger / 2=keep champ |
| `queue-inc-if` | queue-after-assign alias for host fold |

Host projects ok/warm/better per candidate and holds champ index outside guest.

## Evidence

- `test/murakumo/infer_plan_kotoba_parity_test.clj` (`partition-step-fold-matches-n4-cljc`)
- `test/murakumo/infer_schedule_kotoba_parity_test.clj` (`pick-fold-step-n4-matches-schedule-pick`)

## Related

- inventory Next: n>3 assign/partition reduce
- murakumo#68–#69 partition-3, #73 pick, #76–#79 assign steps
