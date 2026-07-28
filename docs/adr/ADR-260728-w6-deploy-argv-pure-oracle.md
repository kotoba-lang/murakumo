# ADR-260728: W6 deploy argv/gate pure oracle expansion

Status: accepted after config ops defaults pure (#148)

## Decision

Expand `kotoba/deploy_plan_core.kotoba` with residual **pin/git argv
fragments** and **deploy CLI validation gates**:

| export | role |
|---|---|
| `cp-bin` / `rm-bin` / `rm-rf-flag` / `cp-recursive-flag` | copy/remove argv bins+flags |
| `git-c-flag` / `git-rev-parse` / `git-short-flag` / `git-head-ref` | git-short-sha argv fragments |
| `version-flag` / `version-bin-path` | version-argv path + flag |
| `build-features` | BUILD.edn `:features` string |
| `missing-manifest?` / `missing-operator-seed?` | deploy-command-error gates (0/1) |

Host dual-source via `:deploy-plan` + `try-oracle` / mirrors. Argv **vector
assembly** and keyword mapping (`:missing-manifest` / `:missing-operator-seed`)
stay host. Windows drive absolute-git remains host mirror.

## Evidence

- regenerated `deploy_plan_core.kir.edn`
- deploy unit + kotoba parity + authority suites green

## Related

- ADR-260728-w6-deploy-probe-pure-oracle
- ADR-260728-w6-deploy-connect-authority-oracle
- murakumo#148 config ops defaults pure
