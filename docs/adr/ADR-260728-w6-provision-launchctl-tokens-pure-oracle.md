# ADR-260728: W6 provision launchctl shell tokens pure oracle expansion

Status: accepted after deploy cmd recompose pure (#190)

## Decision

Expand `kotoba/provision_plan_core.kotoba` with residual **launchctl shell
tokens**, dual-sourced on `murakumo.provision.plan`:

| export | role |
|---|---|
| `launchctl-print-prefix` | `sudo launchctl print system/` |
| `launchctl-bootout-prefix` | `sudo launchctl bootout system/` |
| `launchctl-bootstrap-sys` | `sudo launchctl bootstrap system ` |
| `launchd-daemons-dir` | `/Library/LaunchDaemons/` |
| `launchctl-bootstrap-prefix` | bootstrap-sys + daemons-dir |
| `launchctl-kickstart-prefix` | `sudo launchctl kickstart -k system/` |
| `launchctl-status-suffix` | running/stopped probe suffix |
| `launchctl-plist-quiet-semi` | `.plist 2>/dev/null; ` |
| `launchctl-quiet-true-sleep` | quiet bootout + sleep mid |
| `launchctl-plist-quiet-true-semi` | `.plist 2>/dev/null \|\| true; ` |
| `plist-ext` | `.plist` |

`launch-status` / `launch-up` / `launch-down` / `reprovision` /
`watchdog-reprovision` / `launchd-daemon-path` recompose from these fragments.
SSH / rsync / launchctl *execution* stay host.

## Evidence

- regenerated `provision_plan_core.kir.edn`
- provision parity + authority green

## Related

- ADR-260728-w6-provision-multiaddr-tokens-pure-oracle
- murakumo#190 deploy cmd recompose pure
