# ADR-260731: T6.4 remainder — deploy.plan deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after report #253)
- Depends: #233 preload contract; #226 fleetwide try-oracle JVM

## Decision

1. **`murakumo.deploy.plan` drops all `mirror-*` pure reimplementations and dual-source
   `try-oracle` / `oracle-str-const` / `oracle-i64-const` fallbacks.** Pure path/url,
   probe, argv flag/gate, pin path, and shell-token helpers require the shipped
   `:deploy-plan` KIR on **every** platform via `oracle/require-ready!`.
2. **Host-only remains:** regex extract (`manifest-src` / `manifest-cid`), argv
   vector assembly, node folds / map assembly, filesystem probes (`resolve-git-bin`),
   Windows drive-letter absolute path check in `absolute-git-bin?`.
3. **Preload guarantee (same as prior waves):** nbb `resources/`,
   `register-kir!` / `set-resource-loader!`, JVM classpath.

## Non-claims

- Other dual-source hosts still keep cljs `mirror-*` fail-closed fallback
  (provision/cloud remain)
- T8.3 production AOT network/secret; W4 recursive values

## Evidence

- `deploy-plan-test` + `deploy-plan-kotoba-parity-test` + focused authority green
- No `mirror-*` / `try-oracle` remain in `src/murakumo/deploy/plan.cljc`
