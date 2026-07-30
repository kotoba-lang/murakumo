# ADR-260731: T6.4 remainder — tunnel deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after keyring+peer #241)
- Depends: #233 preload contract; fleetwide try-oracle JVM #226

## Decision

1. **`murakumo.tunnel` drops all `mirror-*` pure reimplementations and the
   dual-source `try-oracle` fallback path.** Pure helpers call the shipped
   `:tunnel` KIR on **every** platform via `oracle/require-ready!`.
2. **Host-only remains:** line-split in `parse-rc`, argv vector assembly
   (`ssh-argv` / `scp-argv` / `conn-opts` structure).
3. **Still numeric (not bool residual for this slice):** `pick-exit` `has-rc`
   is 0/1 i64 presence for the guest ABI (follow-up optional :bool param).
4. **Preload guarantee (same as prior waves):** nbb `resources/`,
   `register-kir!` / `set-resource-loader!`, JVM classpath.

## Non-claims

- Other dual-source hosts still keep cljs `mirror-*` fail-closed fallback
- T8.3 production AOT network/secret; W4 recursive values

## Evidence

- `tunnel-test` + `tunnel-kotoba-parity-test` + focused authority green
- No `mirror-*` / `try-oracle` remain in `src/murakumo/tunnel.cljc`
