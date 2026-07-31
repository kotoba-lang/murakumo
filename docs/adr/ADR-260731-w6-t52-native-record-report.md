# ADR-260731: T5.2 native guest record wire — report-core

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo native-record waves through provision-plan
- WBS: T5.2 native guest record wire expansion

## Decision

Fold multi-scalar pure inputs on `report_core` into single guest records
(pair/triple/pad/join/csv/nodes-row/action-detail/app-row/title/cid/ports/…).

Host builds `oracle/record` + `call-record` `:raw`.

## Non-claims

- Single-arg residual (node-prefix, missing-binary, drift-line, watch-start, …) stay scalar.
- `status-row` / `health-label` stay positional (option residual).
- Internal digit/space helpers stay multi-arg where recursive scanners.
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for `report_core` only
- Focused report parity + authority + unit green
