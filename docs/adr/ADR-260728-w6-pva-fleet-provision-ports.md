# ADR-260728: Product Value ABI v1 — fleet + provision port resolvers

Status: accepted after token PVA (#112)

## Decision

Expand Product Value ABI v1 beyond token to optional **port** fields:

| core | export | before | after |
|---|---|---|---|
| `fleet_inventory_core` | `resolve-port` | `has-node`/`has-fleet` + zero fillers | `[:option :i64]` ×2 + `if-some` |
| `provision_plan_core` | `resolve-p2p-port` | same sentinel pattern | `[:option :i64]` ×2 + `if-some` |

Host bridges via `murakumo.kotoba.oracle/option-i64`. Defaults remain 8077 (control) and 4001 (p2p).

Also: `health-url` uses `string-from-i64` (decimal without hand `nat-str` path for port).

### Still host / unchanged

- Selector set algebra, offline-line string scan
- multiaddr/webrtc string assembly beyond resolve-p2p
- bit-packed eligibility flags (schedule/join) — later PVA slices

## Evidence

- fleet_inventory + provision parity + authority suite
- regenerated `fleet_inventory_core.kir.edn` + `provision_plan_core.kir.edn`

## Related

- murakumo#112 Product Value ABI v1 token
- inventory Next: PVA expand / Delivery shells
