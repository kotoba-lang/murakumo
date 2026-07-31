# ADR-260731: T5.2 native guest record — string-scan + digit-scanner residual

- Status: accepted
- Date: 2026-08-01
- Depends: ends-idx residual (f9c6a6cf), rebalance seat-order (2e97d098)
- WBS: T5.2 residual multi-arg pure (string-scan / digit scanners)

## Decision

Fold remaining string-scan and digit-scanner multi-arg pure into single guest
records:

| Core | Export | Schema |
|------|--------|--------|
| `cloud_plan` | `flag-value-after` | `:cloud/flag-after` |
| `deploy_plan` | `last-slash-index` | `:deploy/last-slash` |
| `deploy_plan` | `digit-of-go` | `:deploy/digit-of-go` |
| `deploy_plan` | `parse-digits-go` | `:deploy/digits-go` |
| `provision_plan` | `find-prefix-at` | `:provision/prefix-at` |
| `provision_plan` | `take-alnum` | `:provision/take-alnum` |
| `dash_state` | `parse-digits-go` | `:dash/digits-go` |
| `reconcile_plan` | `digit-of-go` / `parse-digits-go` | `:reconcile/digit-of-go` / `:reconcile/digits-go` |
| `tunnel` | `parse-digits-go` | `:tunnel/digits-go` |

Internal recompose (flag-*-value, manifest-dir, peer-id-from-log, digit-of,
parse-digits) packs records via `record-new`.

## Non-claims

- T8.3 nested kit EDN still W4-gated
- T8.4 optional L5 / real Node sockets

## Evidence

- KIR regenerated for cloud/deploy/provision/dash/reconcile/tunnel
- parity 41/573 + authority 70/1420 green
