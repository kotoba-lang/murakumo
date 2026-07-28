# ADR-260728: W6 product-shell host wiring — tunnel + fleet.inventory + config

Status: accepted after bulk catalog (#99)

## Decision

Incrementally host-wire three catalog-only cores:

| catalog | host | pure delegates |
|---|---|---|
| `:tunnel` | `murakumo.tunnel` | timeouts, rc-marker, wrap-cmd, conn-opts values, scp-dest, forward/curl cmds, parse-rc marker/digits |
| `:fleet-inventory` | `murakumo.fleet.inventory` | resolve-port, health-url, selector predicates, offline-line |
| `:config` | `murakumo.config` | default paths, kotoba-dir-from, pinned/release/wit path builders |

Vector/argv assembly, EDN I/O, and env map folds stay host.

## Evidence

- authority tests for the three verticals
- existing tunnel / fleet_inventory / config parity + unit tests

## Related

- murakumo#99 bulk catalog
- inventory Next: remaining catalog-only host wiring
