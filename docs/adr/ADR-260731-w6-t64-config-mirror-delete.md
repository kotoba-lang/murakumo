# ADR-260731: T6.4 remainder — config deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after task+reconcile #250)
- Depends: #233 preload contract; #226 fleetwide try-oracle JVM

## Decision

1. **`murakumo.config` drops all `mirror-*` pure reimplementations and dual-source
   `try-oracle` / `oracle-str-const` fallbacks.** Pure path helpers + URL/string
   defaults require the shipped `:config` KIR on **every** platform via
   `oracle/require-ready!`.
2. **Host-only remains:** EDN parse/IO, env map folds, filesystem existence
   probes, process getenv wrappers.
3. **Still numeric (follow-up):** `resolve-local-bin` / `kotoba-bin` /
   `resolve-wit-dir` project pinned-exists as 0/1 i64 guest args.
4. **Preload guarantee (same as prior waves):** nbb `resources/`,
   `register-kir!` / `set-resource-loader!`, JVM classpath.

## Non-claims

- Other dual-source hosts still keep cljs `mirror-*` fail-closed fallback
- T8.3 production AOT network/secret; W4 recursive values

## Evidence

- `config-test` + `config-kotoba-parity-test` + focused authority green
- No `mirror-*` / `try-oracle` remain in `src/murakumo/config.cljc`
