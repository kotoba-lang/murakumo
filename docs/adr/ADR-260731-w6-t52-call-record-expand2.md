# ADR-260731: T5.2 call-record expand wave 2 (token/report/tunnel)

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#261 (wave 1), T5.2 call-record pilot #155
- WBS: T5.2 remainder — positional projection expansion

## Decision

Expand `oracle/call-record` to more map-shaped product host boundaries:

| Host | Export | Map shape |
|------|--------|-----------|
| `token/claims` | `claim-exp` | `{:now :ttl}` |
| `token/encode-claims-json` | `encode-claims-json` | claims map |
| `token/expired?` | `expired?` | `{:exp :now}` |
| `token/parts-present?` | `parts-present?` | segment map |
| `report/status-row` | `status-row` | row fields map |
| `tunnel/pick-exit` | `pick-exit` | `{:has-rc :rc :ssh-exit}` |

Each ns keeps a private `o-record` that `require-ready!`s then `call-record`s.

## Non-claims

- Guest export signatures unchanged (still scalar params).
- Native guest `[:record …]` wire remains the T5.3 path (`oracle/record`).
- Not every positional `oracle/call` is converted — only natural host maps.

## Evidence

- `oracle-call-record-test` + token/report/tunnel focused suites green
