# ADR-260728: W6 product-shell oracle authority — dash.state pure path

Status: accepted fifth product-shell dual-source cutover after infer.plan (#91)

## Decision

Wire high-traffic pure helpers of `murakumo.dash.state` to kotoba SSoT:

| layer | role |
|---|---|
| `kotoba/dash_state_core.kotoba` | SSoT |
| `resources/murakumo/oracle/dash_state_core.kir.edn` | precompiled KIR |
| `murakumo.kotoba.oracle` catalog `:dash-state` | load + execute |
| JVM `short-hosted-cid` / `health-class` / `interval-sleep-ms` / `clamp-at` / `append-capped` start / `recent-alerts` n | delegate to oracle |

### Still cljc host

- `placements` / `links-total` / `snapshot-record` / `selected-snapshot` assembly
- `render-html` string join
- probe parse / query-at / response maps
- `diff-alerts` map fold
- cljs host-mirror for pure helpers

## Evidence

- authority + dash_state_kotoba_parity (+ existing dash_state unit tests)

## Related

- inventory Next: expand catalog (schedule/task/engine/…)
- murakumo#86–#91 product-shell pattern
