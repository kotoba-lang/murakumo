# ADR-260731: T6.4 remainder — report deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after dash #252)
- Depends: #233 preload contract; #226 fleetwide try-oracle JVM

## Decision

1. **`murakumo.report` drops all `mirror-*` pure reimplementations and dual-source
   `try-oracle` / `oracle-str-const` / `oracle-i64-const` fallbacks.** Pure line
   builders + CSV folds require the shipped `:report-core` KIR on **every**
   platform via `oracle/require-ready!`.
2. **Host-only remains:** map/keyword projection into guest args, collection
   walks, `reconcile-lines` mapcat over apps, pad width from Clojure string count.
3. **Preload guarantee (same as prior waves):** nbb `resources/`,
   `register-kir!` / `set-resource-loader!`, JVM classpath.

## Non-claims

- Other dual-source hosts still keep cljs `mirror-*` fail-closed fallback
  (deploy/provision/cloud remain)
- T8.3 production AOT network/secret; W4 recursive values

## Evidence

- `report-test` + `report-kotoba-parity-test` + focused authority green
- No `mirror-*` / `try-oracle` remain in `src/murakumo/report.cljc`
