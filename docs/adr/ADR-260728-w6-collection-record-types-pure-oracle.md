# ADR-260728: W6 collection record-type pure oracle expansion

Status: accepted after token version/JWT seps pure (#178)

## Decision

Expand residual **atproto collection / record `$type` NSIDs** for fleet
snapshot and reconcile records into kotoba pure oracles, dual-sourced on host:

| core | export | value |
|---|---|---|
| `dash_state_core` | `snapshot-record-type` | `com.murakumo.fleet.snapshot` |
| `reconcile_plan_core` | `reconcile-record-type` | `com.murakumo.fleet.reconcile` |

Host `murakumo.dash.state/snapshot-record` and
`murakumo.reconcile.plan/reconcile-record` use the dual-sourced tokens for
`:$type`. Map assembly stays host.

These NSIDs match `murakumo.persist` `snapshot-collection` /
`reconcile-collection` (already dual-sourced under `:persist`). Record builders
own the `$type` SSoT for their payload shape; persist owns collection path
constants used in repo URIs/envelopes.

## Evidence

- regenerated `dash_state_core.kir.edn` + `reconcile_plan_core.kir.edn`
- dash/reconcile unit + parity + authority green

## Related

- ADR-260728-w6-cloud-node-type-pure-oracle (cloud `$type` pattern)
- ADR-260728-w6-persist-oracle-authority (collection names)
- murakumo#178 token seps pure
