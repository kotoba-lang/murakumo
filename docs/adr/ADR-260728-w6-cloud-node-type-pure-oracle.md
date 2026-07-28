# ADR-260728: W6 cloud node-record type pure oracle expansion

Status: accepted after task unsched seps pure (#166)

## Decision

Expand `kotoba/cloud_plan_core.kotoba` with residual **record $type strings
and default capability name tokens**, dual-sourced on `murakumo.cloud.plan`:

| export | role |
|---|---|
| `node-record-type` | `cloud.murakumo.node` |
| `route-record-type` | `cloud.murakumo.route` |
| `relay-record-type` | `cloud.murakumo.relay` |
| `policy-record-type` | `cloud.murakumo.policy` |
| `bootstrap-record-type` | `cloud.murakumo.bootstrap` |
| `cap-ssh` / `cap-http` / `cap-gossip` / `cap-deploy` / `cap-reconcile` | default capability names |

Host `node-record` / `route-record` / `relay-record` / `policy-record` /
`bootstrap-manifest` dual-source `$type`; default capabilities keywordized from
cap-* names. Map assembly stays host.

## Evidence

- regenerated `cloud_plan_core.kir.edn`
- cloud unit/parity + authority green

## Related

- ADR-260728-w6-cloud-parse-flags-pure-oracle
- murakumo#166 task unsched seps pure
