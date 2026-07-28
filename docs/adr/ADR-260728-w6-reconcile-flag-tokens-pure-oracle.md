# ADR-260728: W6 reconcile flag/action tokens pure oracle expansion

Status: accepted after cloud cmd/flag tokens pure (#173)

## Decision

Expand `kotoba/reconcile_plan_core.kotoba` with residual **CLI flag tokens and
action name tokens**, dual-sourced on `murakumo.reconcile.plan`:

| export | role |
|---|---|
| `flag-dry-run` / `flag-apply` / `flag-watch` | exact flag tokens |
| `flag-watch-eq-prefix` / `flag-snapshot-prefix` / `flag-dash-prefix` | prefixes |
| `action-satisfied`…`action-needs-build` | action-name tokens |
| recomposed `flag-is-*` / `watch-seconds` / `snapshot-value` / `action-is-*` / `action-name` | from tokens |

Host parse-flags reduce fold stays host. Flag/action string SSoT is kotoba.

## Evidence

- regenerated `reconcile_plan_core.kir.edn`
- reconcile unit + parity + authority green

## Related

- ADR-260728-w6-reconcile-flags-pure-oracle
- ADR-260728-w6-cloud-cmd-tokens-pure-oracle
- murakumo#173 cloud cmd/flag tokens pure
