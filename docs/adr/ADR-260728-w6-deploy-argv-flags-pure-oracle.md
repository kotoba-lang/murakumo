# ADR-260728: W6 deploy argv flags pure oracle expansion

Status: accepted after dash defaults pure (#162)

## Decision

Expand `kotoba/deploy_plan_core.kotoba` with residual **component/app/block
argv subcommand and flag fragments**, dual-sourced on `murakumo.deploy.plan`:

| export | role |
|---|---|
| `component-subcmd` / `build-subcmd` | `component` / `build` |
| `app-subcmd` / `deploy-subcmd` | `app` / `deploy` |
| `wit-dir-flag` / `output-flag` | `--wit-dir` / `-o` |
| `publish-flag` / `url-flag` | `--publish` / `--url` |
| `token-flag` / `file-flag` | `--token` / `--file` |
| `block-subcmd` / `put-subcmd` | `block` / `put` |

`component-build-cmd` / `app-deploy-cmd` recompose those fragments.
Host `component-build-argv` / `app-deploy-argv` / `block-put-argv` dual-source
the fragments; vector assembly stays host.

## Evidence

- regenerated `deploy_plan_core.kir.edn`
- deploy unit/parity + authority green

## Related

- ADR-260728-w6-deploy-pin-path-pure-oracle
- murakumo#162 dash defaults pure
