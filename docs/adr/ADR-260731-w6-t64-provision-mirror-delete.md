# ADR-260731: T6.4 remainder — provision.plan deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after deploy #254)
- Depends: #233 preload contract; #226 fleetwide try-oracle JVM

## Decision

1. **`murakumo.provision.plan` drops all `mirror-*` pure reimplementations and dual-source
   `try-oracle` / `oracle-str-const` / `oracle-i64-const` fallbacks.** Pure path/port,
   launchctl/rsync/peer shell tokens, peer-id scan, plist placeholder replace, and
   fold steps require the shipped `:provision-plan` KIR on **every** platform via
   `oracle/require-ready!`.
2. **Host-only remains:** collection walks, connect transport class checks,
   inventory port lookup, map assembly / fold structure over nodes, host-rendered
   XML plist body content.
3. **Preload guarantee (same as prior waves):** nbb `resources/`,
   `register-kir!` / `set-resource-loader!`, JVM classpath.

## Non-claims

- Other dual-source hosts still keep cljs `mirror-*` fail-closed fallback
  (cloud remains the last T6.4 XL shell)
- T8.3 production AOT network/secret; W4 recursive values

## Evidence

- `provision-plan-test` + `provision-plan-kotoba-parity-test` + focused authority green
- No `mirror-*` / `try-oracle` remain in `src/murakumo/provision/plan.cljc`
