# ADR-260728: W6 crypto/host-shell pure — identity templates + overlay packaging

Status: accepted crypto/host-shell pure packaging slice after #61–#83

## Decision

Port remaining **pure** string/constant packaging for crypto-adjacent shells.
Encrypt/HMAC/SHA-256 stay host forever in this cutover.

Token wire JSON + constant-time eq already landed in murakumo#83
(`ADR-260728-w6-token-hmac-wire-oracle.md`); this slice does not reopen token.

| artifact | host | pure surface |
|---|---|---|
| `kotoba/identity_core.kotoba` | `murakumo.identity` | seed preimages (existing #46) + JWT header/payload templates + op-token sig segment + CID `b` prefix |
| `kotoba/overlay_crypto_core.kotoba` | `murakumo.overlay.crypto` | alg name, nonce 12, GCM tag 128, sealed field names, b64url pad strip |

### Pure vs host

| pure (kotoba) | host (cljc/clj) |
|---|---|
| `operator-seed:node` / `:p2p` / `:x25519` / overlay-auth preimages | `sha256-hex` of those preimages |
| JWT JSON templates for op-token | `b64url` encode + `"."` join |
| `"aes-256-gcm"`, nonce-bytes=12, tag-bits=128, field names | AES-GCM seal/open, SecureRandom, `SecretKeySpec` |
| strip `=` from b64 strings | Base64 URL encode/decode of bytes |

### Not ported

- SHA-256 / base32 CID / AES-GCM / HMAC-SHA256
- Random nonce generation
- Full base64url alphabet encode/decode of raw bytes

## Evidence

- `test/murakumo/identity_kotoba_parity_test.clj`
- `test/murakumo/overlay_crypto_kotoba_parity_test.clj`

## Related

- inventory Next: crypto/host shells after #82; token wire #83 parallel
- murakumo#46 identity seed preimages
- `src/murakumo/overlay/crypto.clj` remains AES-GCM host shell
