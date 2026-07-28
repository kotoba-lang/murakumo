# ADR-260728: W6 reconcile.plan flag/action pure oracle expansion

Status: accepted after provision shell pure (#139)

## Decision

Expand `kotoba/reconcile_plan_core.kotoba` with residual pure helpers for the
CLI shell and plan gates:

| export | role |
|---|---|
| `missing-manifest?` | reconcile-command-error gate |
| `action-is-satisfied?` / `action-is-place?` | plan-converged? / apply-apps gates |
| `flag-is-dry-run?` / `flag-is-apply?` / `flag-is-watch?` / `flag-is-snapshot?` / `flag-is-dash?` | parse-flags classifiers |
| `watch-seconds` / `default-watch-seconds` | `--watch` / `--watch=N` |
| `snapshot-value` | `--snapshot=PATH` extract |
| `starts-with?` / digit parse helpers | shared string utils |

Host dual-source via `:reconcile-plan` + `try-oracle`. The `parse-flags` reduce
fold and eligible/pick map algebra stay host.

## Evidence

- regenerated `reconcile_plan_core.kir.edn`
- reconcile unit + kotoba parity + authority green

## Related

- murakumo#101 reconcile oracle authority
- murakumo#139 provision shell pure
