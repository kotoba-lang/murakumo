# ADR-260728: W6 deploy.plan probe/pin pure oracle expansion

Status: accepted after dash probe pure (#136)

## Decision

Expand `kotoba/deploy_plan_core.kotoba` with residual pure helpers used by
placement probes and pin planning:

| export | role |
|---|---|
| `execution-observed?` | trim + digits → pos? (0 on empty/non-digit) |
| `execution-count-command` | remote grep count shell string |
| `release-wit-path` / `release-wit-suffix` | pin WIT path from release dir |
| `stop-forward-command` | pkill local-port forward |
| `absolute-unix-git-bin?` | `/…` absolute + not bare `git` (Windows drive stays host) |
| `pin-bin-kotoba` / `pin-bin-server` | pin binary names |

Host dual-source via `:deploy-plan` + `try-oracle` / mirrors.

### Still host

| concern | why |
|---|---|
| `manifest-src` / `manifest-cid` regex extract | host regex |
| argv vectors / pin-copy-plan folds | collection assembly |
| `resolve-git-bin` exists?/execute probe | filesystem |
| Windows drive absolute path | host mirror only |

## Evidence

- regenerated `deploy_plan_core.kir.edn`
- deploy unit + kotoba parity + authority suites green

## Related

- murakumo#110 deploy-plan oracle authority
- murakumo#136 dash probe pure expansion
