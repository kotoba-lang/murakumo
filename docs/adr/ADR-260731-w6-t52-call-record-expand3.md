# ADR-260731: T5.2 call-record expand wave 3

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#261–#262
- WBS: T5.2 remainder — positional projection expansion

## Decision

Expand `oracle/call-record` to more composite host boundaries:

| Host | Export |
|------|--------|
| `reconcile.plan` | `desired`, `deficit`, `action-name` |
| `tunnel` | `ensure-forward-command`, `replace-forward-command` |
| `infer.relay` | `lease-expired?` |
| `cloud.plan` | `overlay-id-input`, `node-id-input`, `node-region` |

Each ns keeps private `o-record` (`require-ready!` + `call-record`).

## Non-claims

- Guest export signatures unchanged.
- Native guest `[:record …]` wire remains T5.3 path.
- T8.3 production AOT network/secret remains open.

## Evidence

- `oracle-call-record-test` + reconcile/tunnel/relay/cloud focused suites green
