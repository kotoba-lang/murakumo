# ADR-260731: T6.4 remainder — overlay crypto + stream delete cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion wave after kekkai #233 / token #235 / secret #236 / identity #237)
- Depends: #233 preload contract; #225 JVM oracle-required for crypto/stream

## Decision

1. **`murakumo.overlay.crypto` and `murakumo.overlay.stream` drop all
   `#?(:cljs … mirror-*)` pure reimplementations and dual-source `o` fallbacks.**
   Pure helpers call shipped `:overlay-crypto` / `:overlay-stream` KIR on
   **every** platform via `oracle/require-ready!`.
2. **Host-only remains:**
   - crypto: AES-GCM Cipher, SecureRandom nonce, SHA-256 key material (JVM)
   - stream: stream-id hashing, frame/open-stream map assembly
3. **Preload guarantee (same as prior waves):** nbb `resources/`,
   `register-kir!` / `set-resource-loader!`, JVM classpath.

## Non-claims

- Other dual-source hosts still keep cljs `mirror-*` fail-closed fallback
- T8.3 production AOT network/secret; W4 recursive values

## Evidence

- Focused crypto/stream parity + unit + oracle authority green
- No `mirror-*` / `::oracle-failed` remain in crypto.cljc or stream.cljc
