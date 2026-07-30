# ADR-260731: T6.4 remainder — overlay driver + runtime delete cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after infer-small #245)
- Depends: #233 preload contract; #226 fleetwide try-oracle JVM

## Decision

1. **`murakumo.overlay.driver` and `murakumo.overlay.runtime` drop all
   `mirror-*` pure reimplementations and dual-source `try-oracle` /
   `oracle-str-const` / `oracle-i64-const` fallbacks.** Pure helpers call
   shipped `:overlay-driver` / `:overlay-runtime` KIR on **every** platform via
   `oracle/require-ready!`.
2. **Host-only remains:**
   - driver: parse-argv loops, session/bootstrap maps, dry-run/execute plans
   - runtime: adapter registry maps, URL regex parse, listen/connect specs
3. **Preload guarantee (same as prior waves):** nbb `resources/`,
   `register-kir!` / `set-resource-loader!`, JVM classpath.

## Non-claims

- Other dual-source hosts still keep cljs `mirror-*` fail-closed fallback
- T8.3 production AOT network/secret; W4 recursive values

## Evidence

- Focused driver/runtime unit + parity + oracle authority green
- No `mirror-*` / `try-oracle` remain in driver.cljc or runtime.cljc
