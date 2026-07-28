# ADR-260728: W6 report CSV fold pure oracle expansion

Status: accepted after provision peer-id/plist pure (#170)

## Decision

Expand `kotoba/report_core.kotoba` with residual **CSV join fold steps**,
dual-sourced on `murakumo.report`:

| export | role |
|---|---|
| `join-append` | generic empty-first append (`acc + sep + next`) |
| `csv-append` | comma join step (`report-csv-sep`) |
| `csv-spaced-append` | comma+space join step (`report-csv-spaced-sep`) |

Host `csv-join` / `csv-spaced-join` reduce over item collections for
reconcile targets/running/reach/eligible/misplaced and deploy-observed
where lists. App mapcat structure stays host.

## Evidence

- regenerated `report_core.kir.edn`
- report unit + parity + authority green

## Related

- ADR-260728-w6-report-csv-pure-oracle
- ADR-260728-w6-provision-bootstrap-fold-pure-oracle (same fold-step shape)
- murakumo#170 provision peer-id/plist pure
