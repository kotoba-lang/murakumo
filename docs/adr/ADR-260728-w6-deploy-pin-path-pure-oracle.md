# ADR-260728: W6 deploy pin-path pure oracle expansion

Status: accepted after provision plist-path pure (#159)

## Decision

Expand `kotoba/deploy_plan_core.kotoba` with residual **pin-copy-plan path
fragments**, dual-sourced on `murakumo.deploy.plan`:

| export | role |
|---|---|
| `pin-wit-dirname` | `wit` under pin dest |
| `pin-wit-dest` | `dest/wit` via `join-path` |
| `join-path` | already pure; dual-source on host for pin paths |
| `pin-bin-kotoba` / `pin-bin-server` | dual-source names for `pinned-binaries` |

`pin-copy-plan` uses `join-path` for binary src/dest and `pin-wit-dest` for
the WIT directory. Map assembly of the pin plan stays host.

## Evidence

- regenerated `deploy_plan_core.kir.edn`
- deploy unit/parity + authority green

## Related

- ADR-260728-w6-deploy-argv-pure-oracle
- murakumo#159 provision plist path pure
