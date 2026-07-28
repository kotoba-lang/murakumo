# ADR-260728: W6 cloud parse-flags pure oracle expansion

Status: accepted after provision argv pure (#152)

## Decision

Expand `kotoba/cloud_plan_core.kotoba` with residual **CLI parse-flags
classifiers** (command names + option prefixes + value extractors), dual-
sourced on `murakumo.cloud.plan/parse-flags`:

| export family | role |
|---|---|
| `is-cmd-{plan,records,routes,dial,connect,relay,bootstrap}?` | command tokens |
| `is-flag-{cloud,fleet,target,from,to,capability,driver,format,auth-key}?` | option prefixes |
| `is-flag-dash?` / `is-positional-target?` | residual argv shape |
| `flag-*-value` | value after `=` for each option |
| `starts-with?` | shared prefix helper |

Host keeps the **reduce fold** over argv and keyword mapping (`:from` /
`:capability` / `:format`). Mirrors fall back when oracle is not ready.

## Evidence

- regenerated `cloud_plan_core.kir.edn`
- cloud unit + parity + authority green

## Related

- ADR-260728-w6-cloud-summary-pure-oracle
- ADR-260728-w6-reconcile-flags-pure-oracle (same classifier pattern)
- murakumo#152 provision argv pure
