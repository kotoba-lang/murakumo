# ADR-260728: W6 crypto/host-shell pure — identity templates + overlay packaging + token JSON

Status: accepted crypto/host-shell pure packaging slice after #61–#82

## Decision

Port remaining **pure** string/constant packaging for crypto-adjacent shells.
Encrypt/HMAC/SHA-256 stay host forever in this cutover.

| artifact | host | pure surface |
|---|---|---|
| `kotoba/identity_core.kotoba` | `murakumo.identity` | seed preimages (existing) + JWT header/payload templates + op-token sig segment + CID `b` prefix |
| `kotoba/overlay_crypto_core.kotoba` | `murakumo.overlay.crypto` | alg name, nonce 12, GCM tag 128, sealed field names, b64url pad strip |
| `kotoba/token_core.kotoba` | `murakumo.token` | existing claims/scope + **encode-claims-json** fixed key order |

### Pure vs host

| pure (kotoba) | host (cljc/clj) |
|---|---|
| `operator-seed:node` / `:p2p` / `:x25519` / overlay-auth preimages | `sha256-hex` of those preimages |
| JWT JSON templates for op-token | `b64url` encode + `"."` join |
| `"aes-256-gcm"`, nonce-bytes=12, tag-bits=128, field names | AES-GCM seal/open, SecureRandom, `SecretKeySpec` |
| strip `=` from b64 strings | Base64 URL encode/decode of bytes |
| encode-claims JSON `sub→scope→iat→exp` | b64url + HMAC-SHA256 sign/verify |

### Not ported

- SHA-256 / base32 CID / AES-GCM / HMAC-SHA256
- Random nonce generation
- Full base64url alphabet encode/decode of raw bytes
- JSON escaping of claims (host is also naive / no escape)

## Evidence

- `test/murakumo/identity_kotoba_parity_test.clj`
- `test/murakumo/overlay_crypto_kotoba_parity_test.clj`
- `test/murakumo/token_kotoba_parity_test.clj`

## Related

- inventory Next: crypto/host shells after #82
- murakumo#46 identity seed preimages, #41 token claims core
- `src/murakumo/overlay/crypto.clj` remains AES-GCM host shell
