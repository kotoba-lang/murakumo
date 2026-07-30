# ADR-260731: T5.2 call-record expand wave 4

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#261–#263
- WBS: T5.2 remainder — positional projection expansion

## Decision

Expand `oracle/call-record` to more composite host boundaries:

| Host | Export |
|------|--------|
| `overlay.keyring` | `epoch`, `key-id-input`, `derive-key-input` |
| `persist` | `repo-uri`, `snapshot-rkey`, `reconcile-rkey`, `repo-write-url` |
| `overlay.peer` | `choose-via` |
| `component-authority` | `place-epoch`, `revoke-epoch`, `next-sequence` |

## Non-claims

- Guest export signatures unchanged.
- Native guest `[:record …]` wire remains T5.3 path.
- T8.3 ops/network AOT Components remain open.

## Evidence

- `oracle-call-record-test` + keyring/persist/peer/cauth suites green
