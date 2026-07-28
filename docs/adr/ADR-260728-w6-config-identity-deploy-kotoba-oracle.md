# ADR-260728: W6 pure-planner oracle — config / identity / deploy path cores

Status: accepted medium-priority cutover slice of `murakumo-pure-planners-v2-medium`

## Decision

Port pure string/path cores for three medium-priority modules:

| artifact | cljc | notes |
|---|---|---|
| `kotoba/config_core.kotoba` | `murakumo.config` | default paths, bin/wit resolution |
| `kotoba/identity_core.kotoba` | `murakumo.identity` | trim, seed preimages, did-from-output |
| `kotoba/deploy_plan_core.kotoba` | `murakumo.deploy.plan` | constants, manifest-dir, joined argv |

### Not ported

- EDN parse/file I/O, env map folds (config host)
- SHA-256 / base32 CID / base64url JWT (identity crypto)
- Vector argv maps, artifact node plans, regex `:src`/`:cid` extract (deploy)

## Evidence

- `test/murakumo/config_kotoba_parity_test.clj`
- `test/murakumo/identity_kotoba_parity_test.clj`
- `test/murakumo/deploy_plan_kotoba_parity_test.clj`

## Related

- murakumo#43–#45 medium pure-planner oracles
