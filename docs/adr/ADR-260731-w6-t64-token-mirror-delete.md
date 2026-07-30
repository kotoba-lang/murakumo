# ADR-260731: T6.4 remainder — token deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion wave 2 after kekkai pilot)
- Depends: #233 kekkai mirror delete + oracle preload contract, #221 same-artifact

## Decision

1. **`murakumo.token` drops all `#?(:cljs … mirror-*)` pure reimplementations
   and the dual-source `try-oracle` / `cljs-str` / `cljs-i64` fallback path.**
   Pure helpers call the shipped `:token` KIR on **every** platform via
   `oracle/require-ready!`.
2. **Host-only remains:** base64url codecs + HMAC-SHA256 (javax / WebCrypto).
3. **Preload guarantee (same as kekkai):**
   - nbb from repo root: `resources/` via default node loader
   - bundler/browser: `register-kir!` / `set-resource-loader!` before require
   - JVM tests/classpath: resource on classpath (existing)

## Non-claims

- Other dual-source hosts still keep cljs `mirror-*` fail-closed fallback
- T8.3 production AOT network/secret; W4 recursive values
- Worker secret resolution (`murakumo.secret`) — separate host

## Evidence

- `token-test` + `token-kotoba-parity-test` + focused authority green
- No `#?(:cljs` mirror bodies remain in `src/murakumo/token.cljc`
