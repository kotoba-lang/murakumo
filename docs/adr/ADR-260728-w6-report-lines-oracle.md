# ADR-260728: W6 report ops-line kotoba oracle extension

- Status: Accepted
- Date: 2026-07-28

## Context

`report_core.kotoba` (murakumo#54) covered mesh/provision constants and
command-error mapping. Many additional pure format lines in `murakumo.report`
remained cljc-only (launch, rollout, dashboard, alerts, deploy-observed).

## Decision

Extend `kotoba/report_core.kotoba` with non-padded format strings:

| family | functions |
|---|---|
| launch / rollout / peers | `launch-result-line`, `rollout-line`, `collected-peers-line` |
| deploy observe | `deploy-observed-empty-line`, `deploy-observed-placed-line` |
| dashboard / watch / apply | `dashboard-start-line`, `watch-start-line`, `apply-target-line` |
| alerts / artifacts | `alert-line`, `artifact-node-status`, `deploy-command-output` |
| pin missing | `missing-pinned-binaries-line1/2` |
| dry-run | `reconcile-dry-run-line` |

### Still cljc

Column-padded tables (`nodes-header/row`, `status-header/row`, `reconcile-lines`)
and multi-line `command-help`.

## Evidence

- `test/murakumo/report_kotoba_parity_test.clj`
