# ADR-260728: W6 dash hosted-summary fold pure oracle expansion

Status: accepted after report CSV fold pure (#171)

## Decision

Expand `kotoba/dash_state_core.kotoba` with residual **hosted-summary join
fold steps**, dual-sourced on `murakumo.dash.state`:

| export | role |
|---|---|
| `join-append` | generic empty-first append (`acc + sep + next`) |
| `hosted-append` | space join step (`hosted-join-sep`) |

Host `hosted-summary` reduces over hosted CIDs after `short-hosted-cid`
projection. Empty hosted stays `nil` on host. Map/vector HTML folds stay host.

## Evidence

- regenerated `dash_state_core.kir.edn`
- dash unit + parity + authority green

## Related

- ADR-260728-w6-dash-defaults-pure-oracle
- ADR-260728-w6-report-csv-fold-pure-oracle (same fold-step shape)
- murakumo#171 report CSV fold pure
