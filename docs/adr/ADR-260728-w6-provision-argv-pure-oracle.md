# ADR-260728: W6 provision rsync/launchd argv pure oracle expansion

Status: accepted after cloud summary pure (#151)

## Decision

Expand `kotoba/provision_plan_core.kotoba` with residual **rsync argv
fragments**, **remote path builders**, and **launchd tee/path** pure helpers:

| export | role |
|---|---|
| `rsync-bin` / `rsync-az-flag` / `rsync-e-flag` | rsync argv bins+flags |
| `local-bin-path` / `remote-bin-dest` | rsync src/dest path assembly |
| `launchd-daemon-path` | `/Library/LaunchDaemons/{label}.plist` |
| `tee-plist-prefix` | `sudo tee {path} >/dev/null <<'PLIST'\n` |
| `plist-heredoc-footer` | `\nPLIST` closer |
| `label-kv` | single `k=v` pair (comma join stays host) |

Host dual-source via `:provision-plan`. Argv **vector** assembly, label map
fold, bootstrap fold, peer-id regex, and plist **body** content stay host.

## Evidence

- regenerated `provision_plan_core.kir.edn`
- provision unit + kotoba parity + authority green

## Related

- ADR-260728-w6-provision-shell-pure-oracle
- ADR-260728-w6-deploy-argv-pure-oracle
- murakumo#151 cloud summary pure
