# ADR-260728: Product Value ABI v1 — report health + connect plane options

Status: accepted after sealed/reconcile PVA (#115)

## Decision

Expand Product Value ABI v1 to report health presence and connect plane flags:

| core | export | before | after |
|---|---|---|---|
| `report_core` | `health-label` | `has-health` i64 | `[:option :string]` + `if-some` |
| `report_core` | `status-row` | `has-health` i64 | `[:option :string]` health |
| `connect_core` | `serves-read?` | `has-http` i64 | `[:option :string]` http |
| `connect_core` | `serves-live?` | `has-common` i64 | `[:option :string]` common transport |
| `connect_core` | `serves-plane?` | has-http + has-common | both as options |

Host bridges via `murakumo.kotoba.oracle/option-string`. Health presence projects as some `"ok"`; connect projects `"http"` / first common live transport name (sorted).

### Still host / unchanged

- Map projection / CSV joins for report rows
- class-transports set algebra for connect
- schedule/join bit-packed eligibility flags
- task `failed?` exit/error optionals (later PVA)

## Evidence

- report + connect parity + authority suite
- regenerated `report_core.kir.edn` + `connect_core.kir.edn`

## Related

- murakumo#112–#115 Product Value ABI v1 slices
- inventory Next: PVA expand (schedule flags, report has-health, connect plane, …)
