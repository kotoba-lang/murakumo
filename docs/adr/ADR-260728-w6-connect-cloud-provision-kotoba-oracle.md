# ADR-260728: W6 pure-planner oracle — connect / cloud.plan / provision.plan

Status: accepted low-priority cutover slice after optional pure oracles

## Decision

| artifact | cljc | notes |
|---|---|---|
| `connect_core.kotoba` | `murakumo.connect` | class defaults + host-projected serves flags |
| `cloud_plan_core.kotoba` | `murakumo.cloud.plan` | defaults, region/score, id preimages, endpoints |
| `provision_plan_core.kotoba` | `murakumo.provision.plan` | paths/ports/multiaddr/command constants |

### Not ported

- connect set intersection / load-connect EDN
- cloud choose-relay sort + record maps + dial policy
- provision bootstrap fold, peer-id regex, plist render

## Evidence

- `test/murakumo/connect_kotoba_parity_test.clj`
- `test/murakumo/cloud_plan_kotoba_parity_test.clj`
- `test/murakumo/provision_plan_kotoba_parity_test.clj`
