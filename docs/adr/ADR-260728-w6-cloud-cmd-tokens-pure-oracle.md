# ADR-260728: W6 cloud command/flag tokens pure oracle expansion

Status: accepted after dash hosted-summary fold pure (#172)

## Decision

Expand `kotoba/cloud_plan_core.kotoba` with residual **CLI command and flag
prefix tokens**, dual-sourced on `murakumo.cloud.plan`:

| export family | role |
|---|---|
| `cmd-plan`…`cmd-bootstrap` | command name tokens |
| `default-command-token` | parse-flags initial command (`plan`) |
| `command-token` | argv → command name or `""` |
| `flag-*-prefix` / `flag-dash-prefix` | option prefixes |
| recomposed `is-cmd-*` / `is-flag-*` / `flag-*-value` | from tokens + `string-byte-length` |

Host `parse-flags` uses `command-token` + `(keyword …)` for commands; reduce
fold and keyword mapping for flags stay host.

## Evidence

- regenerated `cloud_plan_core.kir.edn`
- cloud unit + parity + authority green

## Related

- ADR-260728-w6-cloud-parse-flags-pure-oracle
- ADR-260728-w6-cloud-node-type-pure-oracle
- murakumo#172 dash hosted fold pure
