# ADR-260728: W6 provision.plan shell pure oracle expansion

Status: accepted after deploy probe pure (#138)

## Decision

Expand `kotoba/provision_plan_core.kotoba` with residual pure shell-command
strings used by mesh/provision ops (SSH host-forever still executes them):

| export | role |
|---|---|
| `launch-status-command` | launchctl print running/stopped |
| `launch-up-command` / `launch-down-command` | bootstrap+kickstart / bootout |
| `reprovision-command` | bootout → bootstrap → kickstart mesh |
| `peer-id-log-command` | grep latest did:key from mesh.log |
| `live-link-count-command` / `live-link-count-output` | peer count + trim |
| `watchdog-label` / `watchdog-reprovision-command` | HTTP-wedge watchdog daemon |

Host dual-source via `:provision-plan` + `try-oracle` / mirrors.

### Still host

| concern | why |
|---|---|
| `write-plist-command` / `write-watchdog-plist-command` | heredoc quoting |
| `peer-id-from-log` regex | host regex extract |
| bootstrap-str / render-plist folds | collection + template replace |

## Evidence

- regenerated `provision_plan_core.kir.edn`
- provision unit + kotoba parity + authority green

## Related

- murakumo#111 provision oracle authority
- murakumo#135 cljs dual-source residual
- murakumo#138 deploy probe pure
