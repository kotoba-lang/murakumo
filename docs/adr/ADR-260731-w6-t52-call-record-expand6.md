# ADR-260731: T5.2 call-record expand wave 6

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#261–#266
- WBS: T5.2 remainder — positional projection expansion

## Decision

Expand `oracle/call-record` to more composite host boundaries:

| Host | Export |
|------|--------|
| `dash.state` | `join-append`, `hosted-append`, `short-hosted-cid`, `health-class-of`, `interval-sleep-ms`, `clamp-at` |
| `kekkai.gate` | `default-kekkai-dir-under`, `parse-status-out`, `authorized?`, `denial-line-of` |
| `overlay.driver` | `option-name`, `blank?`, `endpoint-kind` |
| `overlay.runtime` | `default-port-for-kind`, `adapter-kind`, `known-adapter?`, `scheme-prefix-host` |

## Non-claims

- Guest export signatures unchanged.
- Native guest `[:record …]` wire remains T5.3 path.
- T8.3 nested kit EDN codec remains W4-gated.

## Evidence

- `oracle-call-record-test` + dash/kekkai/driver/runtime suites green
