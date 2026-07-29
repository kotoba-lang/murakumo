# ADR-260728: W6 deploy shell tokens pure oracle expansion

Status: accepted after provision launchctl shell tokens pure (#191)

## Decision

Expand `kotoba/deploy_plan_core.kotoba` with residual **path-sep and
execution/stop-forward shell tokens**, dual-sourced on `murakumo.deploy.plan`:

| export | role |
|---|---|
| `path-sep` | `/` path segment separator |
| `exec-count-prefix` | `grep -c 'trigger: executed.*` |
| `exec-count-suffix` | `' ~/.murakumo/mesh.log 2>/dev/null` |
| `pkill-f-prefix` | `pkill -f '` |
| `stop-forward-suffix` | `:localhost' 2>/dev/null` |

`join-path` / `app-manifest-path` / `last-slash-index` /
`execution-count-command` / `stop-forward-command` recompose from these
fragments. Host argv vector assembly and SSH/exec stay host.

## Evidence

- regenerated `deploy_plan_core.kir.edn`
- deploy parity + authority green

## Related

- ADR-260728-w6-deploy-cmd-recompose-pure-oracle
- murakumo#191 provision launchctl shell tokens pure
