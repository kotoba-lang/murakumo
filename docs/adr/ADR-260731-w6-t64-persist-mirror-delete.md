# ADR-260731: T6.4 remainder — persist deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after connect #239)
- Depends: #233 preload contract; fleetwide try-oracle JVM #226

## Decision

1. **`murakumo.persist` drops all `mirror-*` pure reimplementations and the
   dual-source `try-oracle` / `oracle-const` / `oracle-i64-const` fallback path.**
   Pure helpers call the shipped `:persist` KIR on **every** platform via
   `oracle/require-ready!`.
2. **Host-only remains:** envelope maps, graph-cid hashing, tunnel forward
   argv assembly, curl argv list construction.
3. **Preload guarantee (same as prior waves):** nbb `resources/`,
   `register-kir!` / `set-resource-loader!`, JVM classpath.

## Non-claims

- Other dual-source hosts still keep cljs `mirror-*` fail-closed fallback
- T8.3 production AOT network/secret; W4 recursive values

## Evidence

- `persist-test` + `persist-kotoba-parity-test` + focused authority green
- No `mirror-*` / `try-oracle` remain in `src/murakumo/persist.cljc`
