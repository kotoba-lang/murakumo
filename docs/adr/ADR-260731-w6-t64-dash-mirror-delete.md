# ADR-260731: T6.4 remainder — dash.state deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after config #251)
- Depends: #251 config, preload contract (#233)

## Decision

1. **`murakumo.dash.state` drops all `mirror-*` / `try-oracle` /
   `oracle-str-const` / `oracle-i64-const` dual-source pure reimplementations.**
   Pure display/probe/parse helpers + defaults require shipped `:dash-state`
   KIR via `oracle/require-ready!`.
2. **Host-only remains:** map/vector folds, HTML string assembly, probe-lines
   fold, parse-hosted split, query-string regex, set algebra for alerts.
3. **Preload guarantee:** `ops.cljs` already preloads `:dash-state`.

## Non-claims

- report/deploy/provision/cloud still keep cljs mirrors
- `health-from-present` still takes 0/1 i64 presence (param-bool residual)
- T8.3 production AOT; W4 recursive values

## Evidence

- dash-state host + kotoba parity suites green
- No dual-source mirror bodies remain in `src/murakumo/dash/state.cljc`
