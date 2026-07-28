# ADR-260728: W6 fleet inventory selector/URL tokens pure oracle expansion

Status: accepted after collection record types pure (#179)

## Decision

Expand `kotoba/fleet_inventory_core.kotoba` with residual **selector / offline /
health URL tokens**, dual-sourced on `murakumo.fleet.inventory`:

| export | role |
|---|---|
| `default-control-port` | host dual-source (was hard const 8077 in mirror) |
| `selector-all` | `"all"` CLI selector |
| `offline-token` | `"offline"` tailscale marker |
| `health-url-prefix` | `"http://localhost:"` |
| `health-url-path` | `"/health"` |
| `selector-join-sep` | `","` between selector names |

`resolve-port` / `health-url` / `selector-is-all?` / `selector-wants-name?` /
`line-has-offline?` recompose from those tokens. Vector folds stay host.

## Evidence

- regenerated `fleet_inventory_core.kir.edn`
- fleet inventory unit/parity + authority green

## Related

- ADR-260728-w6-fleet-inventory-oracle-authority
- murakumo#179 collection record types pure
