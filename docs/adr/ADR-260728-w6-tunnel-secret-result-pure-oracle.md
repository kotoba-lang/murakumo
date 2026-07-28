# ADR-260728: W6 tunnel result + secret kit-reply pure oracle

Status: accepted after persist envelope pure (#141)

## Decision

### Tunnel (`tunnel_core`)

| export | role |
|---|---|
| `pick-exit` | sh-result: prefer in-band rc when present |
| `trim-err` / `err-ws?` | stderr trim for sh-result / scp-result |

### Secret (`secret_core`)

| export | role |
|---|---|
| `secret-error-code` / `secret-error-message` | kit-shaped error fields from classify class |
| `reply-is-value?` | value vs error branch |

Host dual-source:

- `tunnel/sh-result` + `scp-result` use pick-exit + trim-err
- `secret/map-fetch` + `fn-fetch` use `classify-fetched` + kit reply builders

SSH subprocess and env/kagi host reads stay host.

## Evidence

- regenerated tunnel_core + secret_core KIR
- tunnel + secret unit/parity + authority green

## Related

- murakumo#100 tunnel oracle authority
- murakumo#98 secret oracle authority
- murakumo#141 persist envelope pure
