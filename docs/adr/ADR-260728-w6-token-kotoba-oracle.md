# ADR-260728: W6 pure-planner oracle — token claims/scope core

Status: accepted fifth cutover slice of `murakumo-pure-planners-v1`

## Decision

Port the pure claims/scope core of `murakumo.token` to
`kotoba/token_core.kotoba`:

| function | notes |
|---|---|
| `version` | `"mk1"` wire prefix |
| `default-ttl` | 2592000 (30d) |
| `claim-sub` / `claim-scope` | nil → anonymous / all |
| `claim-exp` | now + ttl (ttl -1 = default) |
| `expired?` | nil exp or now ≥ exp → 1 |
| `scope-allows?` | `"all"` or exact match |
| `signing-input` | `"mk1." + payloadSeg` |

### Later pure expansion (see ADR-260728-w6-identity-crypto-oracle)

- `encode-claims-json` fixed key order (`sub→scope→iat→exp`) landed with crypto packaging slice

### Still host

- HMAC-SHA256 / base64url (host crypto)
- `sign` / `verify` (platform Mac / WebCrypto)
- `decode-claims` regex/JSON parse (host)

## Evidence

- `test/murakumo/token_kotoba_parity_test.clj`
- Equality against `murakumo.token` offline unit corpus

## Related

- murakumo#37–#40
- `lang/w6-murakumo-path-inventory.edn` first-cutover-slice
