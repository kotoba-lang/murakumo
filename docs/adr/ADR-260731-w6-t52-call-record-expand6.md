# ADR-260731: T5.2 call-record expand wave 6

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#261–#266 (waves 1–5)
- WBS: T5.2 remainder — positional projection expansion

## Decision

Expand `oracle/call-record` to more composite host boundaries:

| Host | Export |
|------|--------|
| `connect` | `default-class-name`, `node-class-name`, `serves-plane?` |
| `dash.state` | `join-append`, `hosted-append`, `health-class-of`, `clamp-at` |
| `overlay.crypto` | `sealed-alg-ok?`, `sealed-fields-present?` |

Each ns keeps private `o-record` (`require-ready!` + `call-record`). Guest
export signatures stay positional scalars; only host projection changes.

## Non-claims

- Guest export signatures unchanged.
- Native guest `[:record …]` wire remains T5.3 path.
- T8.3 nested kit EDN codec remains W4-gated.
- Host AES-GCM seal/open and connect.edn load stay host.

## Evidence

- `oracle-call-record-test` + connect/dash/crypto focused suites green
