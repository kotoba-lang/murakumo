# ADR-260731: T6.4 remainder — cloud.plan deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after provision #255)
- Depends: #233 preload contract; #226 fleetwide try-oracle JVM

## Decision

1. **`murakumo.cloud.plan` drops all `mirror-*` pure reimplementations and dual-source
   `try-oracle` / `oracle-str-const` / `oracle-i64-const` fallbacks.** Defaults,
   endpoints, CLI presentation lines, parse-flags classifiers/tokens, record `$type`
   strings, and capability name tokens require the shipped `:cloud-plan` KIR on
   **every** platform via `oracle/require-ready!`.
2. **Host-only remains:** record assembly, choose-relay sort, width `fmt`, policy walk,
   argv/map assembly, reduce fold for `parse-flags`.
3. **Preload guarantee (same as prior waves):** nbb `resources/`,
   `register-kir!` / `set-resource-loader!`, JVM classpath.

## Non-claims

- T6.4 dual-source product-shell hosts are complete (cloud was the last XL shell).
- T8.3 production AOT network/secret; W4 recursive values remain open.
- Residual 0/1 i64 param projections (config pinned-exists, engine option flags,
  tunnel pick-exit has-rc, …) are separate follow-ups.

## Evidence

- `cloud-plan-test` + `cloud-plan-kotoba-parity-test` green (17 tests / 183 assertions)
- No `mirror-*` / `try-oracle` remain in `src/murakumo/cloud/plan.cljc`
