# ADR-260731: T5.2 call-record expand wave 10

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#261–#273
- WBS: T5.2 remainder — positional projection expansion

## Decision

Expand `oracle/call-record` after wave 9 (#273 engine/schedule/task):

| Host | Export |
|------|--------|
| `report` multi-arg residual | `join-append`, `csv-append`, `csv-spaced-append`, `nodes-row`, `mesh-status`, `status-down-row`, `deploy-observed-placed-line`, `node-prefix`, `provision-result-line`, `launch-result-line`, `missing-pinned-binaries-line1`, `rollout-line`, `collected-peers-line`, `artifact-node-status`, `deploy-start-line`, `deploy-command-output`, `pin-success-line`, `missing-binary-line`, `alert-line`, `snapshot-error-line`, `reconcile-persist-error-line`, `dashboard-start-line`, `apply-target-line`, `watch-start-line`, `command-error-line`, `pad-right`, `cid-display`, `reconcile-title`, `action-detail`, `reconcile-app-row`, `reconcile-app-line`, `reach-line`, `drift-line` |
| `overlay.stream` (first CR) | `advance-seq`, `ack-accepted?` |
| `infer.credits` (first CR) | `memory-time-weight`, `charge-allow?` |
| `infer.rebalance` (first CR) | `usable-gb`, `seats-of-text/media/postproc` |

Closes the last product-shell modules that had **zero** call-record surface
(`stream` / `credits` / `rebalance`) and finishes report's multi-arg CLI
formatters onto the structural host-map bridge.

## Non-claims

- Guest export signatures unchanged.
- Native guest `[:record …]` wire remains T5.3 / language path.
- T8.3 nested kit EDN codec remains W4-gated.
- Zero-arg token/constants stay as `oracle/call` (no structural map).
- Residual single-arg path builders elsewhere (config/deploy/provision/…) may
  still use positional `o` — optional later cleanup, not required for T5.2
  product-shell bridge completeness.

## Evidence

- `oracle-call-record-test` + report/stream/credits/rebalance suites green
