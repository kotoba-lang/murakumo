# ADR-260731: T6.4 remainder — crypto/stream/token oracle-required on JVM

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4
- Depends: kekkai oracle-required (#223)

## Decision

Extend the kekkai.gate pattern to three more product shells:

| Host ns | Oracle id | Notes |
|---------|-----------|--------|
| `murakumo.overlay.crypto` | `:overlay-crypto` | pure packaging only; AES seal/open stays JVM host |
| `murakumo.overlay.stream` | `:overlay-stream` | type tokens + advance-seq + ack-accepted? |
| `murakumo.token` | `:token` | pure wire/claims; HMAC/base64url stay host |

On **:clj**, missing shipped KIR throws. On **:cljs**, private mirrors remain
fail-closed fallback without preload.

## Evidence

crypto + stream + token parity/unit: 20 tests / 142 assertions, 0 failures.
