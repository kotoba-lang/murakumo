# ADR-260728: W6 live HMAC + AES host adapters

Status: accepted after token wire pure (#83) + overlay crypto packaging (#85)

## Decision

Wire pure packaging oracles into **live** host entrypoints without moving
HMAC-SHA256 or AES-GCM into the guest:

### `murakumo.token` (HMAC host adapter)

| pure (kotoba parity) | host |
|---|---|
| `encode-claims-json` / `encode-claims` | `b64url-*` codecs |
| `signing-input` / `wire-token` | `hmac-b64url` (javax / WebCrypto) |
| `version-ok?` / `parts-present?` / `constant-time=` | `sign` / `verify` compose pure+HMAC |

Fixed-key claims JSON on **both** JVM and cljs (drops cljs `JSON.stringify` order).

### `murakumo.overlay.crypto` (AES host adapter)

| pure packaging | host |
|---|---|
| `alg-name` / `cipher-transform` / sizes / fields | `Cipher` AES-GCM |
| `strip-b64-pad` / `sealed-*-ok?` | SecureRandom nonce + SHA-256 key |
| `sealed-map-ok?` gate on `open` | `seal` / `open` I/O |

## Evidence

- `test/murakumo/token_test.cljc` (`live-hmac-adapter-uses-pure-wire`)
- `test/murakumo/overlay_crypto_test.clj` (`live-aes-adapter-packaging-gates`)
- Existing kotoba parity suites for token_core + overlay_crypto_core

## Related

- inventory Next: live HMAC·AES host adapters
- murakumo#83 token wire, #85 overlay crypto packaging
