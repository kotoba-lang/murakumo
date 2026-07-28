# ADR-260728: W6 cloud summary address/policy pure oracle expansion

Status: accepted after cloud CLI lines pure (#150)

## Decision

Expand `kotoba/cloud_plan_core.kotoba` with residual **summary line
builders** that take host-projected scalars (counts as i64, names as strings):

| export | role |
|---|---|
| `address-family-line` | `  address-family {af} ; nodes {n} ; relays {r}` |
| `policy-line` | `  policy default={d} allow={n}` |
| `skipped-reason-suffix` | ` skipped reason={reason}` (name column pad stays host) |

Host dual-source via `:cloud-plan`. Width-padded table rows and collection
folds remain host.

## Evidence

- regenerated `cloud_plan_core.kir.edn`
- cloud unit + parity + authority green

## Related

- ADR-260728-w6-cloud-lines-pure-oracle
- murakumo#150 cloud CLI presentation lines
