# ADR-260728: W6 secret reply-class/error tokens pure oracle expansion

Status: accepted after connect class/plane tokens pure (#181)

## Decision

Expand `kotoba/secret_core.kotoba` with residual **reply class / error message /
PEM-begin tokens**, dual-sourced on `murakumo.secret`:

| export | role |
|---|---|
| `class-value` / `class-not-found` / `class-empty` / `class-fetch` / `class-unknown` | kit classify classes |
| `error-code-prefix` | `"secret/"` for host keyword codes |
| `msg-empty` / `msg-not-found` / `msg-fetch` / `msg-unknown` | error messages |
| `pem-begin-marker` | `"-----BEGIN"` path-ref reject |

`classify-fetched` / `reply-tag` / `secret-error-code` / `secret-error-message` /
`reply-is-value?` / `valid-path-ref-unix?` recompose from those SSoTs.
env-fetch / map-fetch / kagi-fetch stay host.

## Evidence

- regenerated `secret_core.kir.edn`
- secret unit/parity + authority green

## Related

- ADR-260728-w6-secret-oracle-authority
- murakumo#181 connect class/plane tokens pure
