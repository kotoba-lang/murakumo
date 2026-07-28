# ADR-260728: W6 cloud CLI presentation pure oracle expansion

Status: accepted after deploy argv pure (#149)

## Decision

Expand `kotoba/cloud_plan_core.kotoba` with residual **CLI presentation
labels and line templates** used by summary/route/dial/connect/relay/bootstrap
renderers:

| export family | role |
|---|---|
| `dash-placeholder`, section headers/labels | fixed table/section strings |
| `summary-title` / `routes-title` / `bootstrap-title` | title lines |
| `unknown-node-line` / `unknown-relay-line` | error titles |
| `dial-denied-line` / `connect-denied-line` | policy-denied titles |
| `dial-ok-title` / `connect-ok-title` / `relay-ok-title` | ok titles |
| `from-to-cap-reason` / `authorized-line` | request detail lines |
| `relay-fallback-line` / `reason-line` / `indent-argv-line` | detail helpers |

Host dual-source via `:cloud-plan`. **Width-padded row fmt**
(`%-14s` / collection folds) and record assembly stay host.

## Evidence

- regenerated `cloud_plan_core.kir.edn`
- cloud unit + kotoba parity + authority green

## Related

- ADR-260728-w6-cloud-endpoint-pure-oracle
- ADR-260728-w6-overlay-cloud-provision-oracle-authority
- murakumo#149 deploy argv pure
