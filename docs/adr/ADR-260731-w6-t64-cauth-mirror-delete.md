# ADR-260731: T6.4 remainder — component-authority deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after fleet.inventory #243)
- Depends: #233 preload contract; fleetwide try-oracle JVM #226

## Decision

1. **`murakumo.component-authority` drops all `mirror-*` pure reimplementations
   and the dual-source `try-oracle` / `oracle-str-const` / `oracle-i64-const`
   fallback path.** Pure helpers call the shipped `:component-authority` KIR on
   **every** platform via `oracle/require-ready!`.
2. **Host-only remains:** event map assembly, atom transition, ed25519 signing
   (JVM), abi contract validation, UTF-8 length projection for identifier gate.
3. **Preload guarantee (same as prior waves):** nbb `resources/`,
   `register-kir!` / `set-resource-loader!`, JVM classpath.

## Non-claims

- Other dual-source hosts still keep cljs `mirror-*` fail-closed fallback
- T8.3 production AOT network/secret; W4 recursive values

## Evidence

- `component-authority-test` + parity + focused authority green
- No `mirror-*` / `try-oracle` remain in `src/murakumo/component_authority.cljc`
