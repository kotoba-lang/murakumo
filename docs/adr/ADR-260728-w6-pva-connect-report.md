# ADR-260728: Product Value ABI v1 — connect plane flags + report health

Status: accepted after sealed/reconcile PVA (#115)

## Decision

Continue Product Value ABI v1 expansion for remaining `has-*` presence gates:

| core | export | before | after |
|---|---|---|---|
| `connect_core` | `serves-read?` / `serves-live?` / `serves-plane?` | `has-http` / `has-common` i64 | `[:option :i64]` + `if-some` (presence) |
| `report_core` | `health-label` / `status-row` | `has-health` i64 | `[:option :i64]` health? + `if-some` |

Host bridges via `murakumo.kotoba.oracle/option-i64` (some(1) when present).

### Still host / unchanged

- connect class-transports set intersection projection
- report map/CSV joins, reconcile-lines mapcat
- schedule bit-packed eligibility + pick-fold `has-champ` (later PVA)
- overlay-peer choose-via has-direct/has-relay flags (later PVA)

## Evidence

- connect + report parity + authority suite
- regenerated `connect_core.kir.edn` + `report_core.kir.edn`

## Related

- murakumo#112 token PVA; #114 ports; #115 sealed+reconcile
