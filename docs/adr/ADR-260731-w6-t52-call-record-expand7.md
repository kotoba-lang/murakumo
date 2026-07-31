# ADR-260731: T5.2 call-record expand wave 7

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#261–#268
- WBS: T5.2 remainder — positional projection expansion

## Decision

Expand `oracle/call-record` after wave 6 (#268 connect/dash/crypto):

| Host | Export |
|------|--------|
| `dash.state` residual | `short-hosted-cid`, `interval-sleep-ms` |
| `kekkai.gate` | `default-kekkai-dir-under`, `parse-status-out`, `authorized?`, `denial-line-of` |
| `overlay.driver` | `option-name`, `blank?`, `endpoint-kind` |
| `overlay.runtime` | `default-port-for-kind`, `adapter-kind`, `known-adapter?`, `scheme-prefix-host` |

## Non-claims

- Guest export signatures unchanged.
- Native guest `[:record …]` wire remains T5.3 path.
- T8.3 nested kit EDN codec remains W4-gated.
- Does not supersede expand6 ADR (connect/dash/crypto #268).

## Evidence

- `oracle-call-record-test` + kekkai/driver/runtime/dash suites green
