# ADR-260728: W6 product-shell oracle authority — fleet.inventory pure path

Status: accepted after tunnel/config (#100) + reconcile (#101)

## Decision

Host-wire catalog id `:fleet-inventory`:

| layer | role |
|---|---|
| `kotoba/fleet_inventory_core.kotoba` | SSoT |
| `resources/murakumo/oracle/fleet_inventory_core.kir.edn` | precompiled KIR (bulk #99) |
| JVM `node-port` / `node-health-url` / `select` predicates / offline-line | delegate to oracle |

### Still host

- node-named / enrich vector folds
- tailscale-status-result process normalisation
- cljs host-mirror

## Evidence

- authority + fleet_inventory_kotoba_parity

## Related

- murakumo#99 bulk catalog; #100–#101 host wiring trail
