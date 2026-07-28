# ADR-260728: W6 dash defaults pure oracle expansion

Status: accepted after provision peer-id pure (#161)

## Decision

Expand `kotoba/dash_state_core.kotoba` with residual **dashboard CLI defaults
and hosted-summary join fragments**, dual-sourced on `murakumo.dash.state`:

| export | role |
|---|---|
| `short-hosted-cid-max-len` | 18 — max chars for short-hosted-cid |
| `short-cid-max-len` | 14 — max chars for alert/table short-cid |
| `hosted-join-sep` | space between hosted CIDs |
| `default-dashboard-port` | 8899 (i64) |
| `default-dashboard-interval` | 15 (i64) |
| `default-dashboard-port-str` | `"8899"` CLI default |
| `default-dashboard-interval-str` | `"15"` CLI default |

`short-hosted-cid` recomposes max-len; `dashboard-options` and
`hosted-summary` dual-source defaults/join. Map fold + parse-int stay host.

## Evidence

- regenerated `dash_state_core.kir.edn`
- dash unit/parity + authority green

## Related

- ADR-260728-w6-dash-probe-command-pure-oracle
- murakumo#161 provision peer-id pure
