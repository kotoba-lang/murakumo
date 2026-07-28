# ADR-260728: W6 pure-planner oracle — token wire/claims + constant-time eq

Status: accepted after token claims core (#41) + host-shell pure (#82)

## Decision

Extend `token_core.kotoba` with HMAC-adjacent **pure** fragments. Real
HMAC-SHA256 and base64url over raw bytes stay host (javax / WebCrypto).

| export | role |
|---|---|
| `encode-claims-json` | JVM fixed-key JSON for claims |
| `wire-token` | `mk1.<payload>.<sig>` assembly |
| `version-ok?` / `parts-present?` | verify structure gates |
| `constant-time-eq` / `ct-scan` | full-scan string compare for sig |

### Still host

- `hmac-b64url` / `b64url-bytes` / `b64url-decode`
- `sign` / `verify` Mac + WebCrypto

## Evidence

- `test/murakumo/token_kotoba_parity_test.clj`

## Related

- inventory Next: Delivery 5–8 / HMAC crypto host
- murakumo#41 token_core first slice
