# ADR-260731: T6.4 remainder — secret deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion wave 3 after kekkai #233 + token #235)
- Depends: #233 preload contract, #235 token mirror delete

## Decision

1. **`murakumo.secret` drops all `mirror-*` pure reimplementations and the
   dual-source `try-oracle` / `oracle-str-const` fallback path.** Pure helpers
   call the shipped `:secret` KIR on **every** platform via
   `oracle/require-ready!`.
2. **Profile 5 residual:** `classify-fetched` `missing`/`blank` params are
   `:bool` (not 0/1 i64); host passes Clojure booleans directly.
3. **Host-only remains:** env/map/kagi fetch, Windows path-ref drive-letter
   branch, resolve/kit reply assembly.
4. **Preload guarantee (same as kekkai/token):** nbb `resources/`,
   `register-kir!` / `set-resource-loader!`, JVM classpath.

## Non-claims

- Other dual-source hosts still keep cljs `mirror-*` fail-closed fallback
- T8.3 production AOT network/secret; W4 recursive values

## Evidence

- `secret-test` + `secret-kotoba-parity-test` + `secret-kagi-test` green
- No `mirror-*` / `try-oracle` remain in `src/murakumo/secret.cljc`
