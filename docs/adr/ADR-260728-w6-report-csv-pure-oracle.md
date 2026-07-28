# ADR-260728: W6 report CSV join pure oracle expansion

Status: accepted after provision plist placeholders pure (#164)

## Decision

Expand `kotoba/report_core.kotoba` with residual **CSV join separators and
CID display length**, dual-sourced on `murakumo.report`:

| export | role |
|---|---|
| `report-csv-sep` | `,` for reconcile targets/running/reach/misplaced |
| `report-csv-spaced-sep` | `, ` for deploy-observed where list |
| `mesh-status-sep` | `/` between binary/launch status |
| `cid-display-max-len` | 16 — host fmt-cid truncate length |

`mesh-status` recomposes `mesh-status-sep`. Host `deploy-observed-row` and
`reconcile-lines` dual-source join seps; mapcat fold stays host.

## Evidence

- regenerated `report_core.kir.edn`
- report unit/parity + authority green

## Related

- ADR-260728-w6-cljs-clj-residual-dual
- murakumo#164 provision plist placeholders pure
