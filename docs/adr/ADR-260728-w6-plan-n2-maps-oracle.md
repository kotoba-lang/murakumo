# ADR-260728: W6 pure-planner oracle — n≠3 plan maps (1/2-node)

Status: accepted after plan-fits-3 compose (#69) + assign-batch (#76)

## Decision

Extend `infer_plan_core.kotoba` beyond the fixed-3 ring so host can assemble
plan assignment maps for the common n=1 and n=2 fleets, attaching node ids
outside the guest:

| export | role |
|---|---|
| `partition-1-end` / `plan-fits-1` | single-rank whole-stack gate |
| `partition-2-ends` / `plan-fits-2` | 2-node ring ends + fits (pack3 hi0,layers,0) |
| `ends-lo` / `ends-hi` | unpack assignment [lo,hi) from ends pack |
| `asg-row-pack` / `asg-row-span` / `asg-row-fits` | span+fits row; host adds `:node` |
| `pick-max-idx-2` | 2-way max usable index (ties keep earlier) |

### Not ported

- n>3 partition walk (variable-arity still cljc)
- plan map assembly with node id strings
- largest-remainder over n≠3 pool vectors (rebalance path separate)

## Evidence

- `test/murakumo/infer_plan_kotoba_parity_test.clj` (`partition-n-neq-3-and-asg-row-maps`)

## Related

- inventory Next: n≠3 node-id plan maps
- murakumo#68 partition-3 walk, #69 plan-fits-3
