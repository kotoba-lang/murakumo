# ADR-260728: W6 product-shell oracle authority — dash.state pure path

Status: accepted fifth product-shell dual-source cutover after infer.plan (#91);
cljs/nbb dual-source extended after #122 (ADR-260728-w6-cljs-oracle-load)

## Decision

Wire high-traffic pure helpers of `murakumo.dash.state` to kotoba SSoT:

| layer | role |
|---|---|
| `kotoba/dash_state_core.kotoba` | SSoT |
| `resources/murakumo/oracle/dash_state_core.kir.edn` | precompiled KIR |
| `murakumo.kotoba.oracle` catalog `:dash-state` | load + execute |
| `short-hosted-cid` / `health-class` / `interval-sleep-ms` / `clamp-at` / `append-capped` start / `recent-alerts` n | dual-source: oracle when `ready?`, else cljc mirror |

### Still cljc host

- `placements` / `links-total` / `snapshot-record` / `selected-snapshot` assembly
- `render-html` string join
- probe parse / query-at / response maps
- `diff-alerts` map fold
- host mirrors remain fallback when oracle is not loadable

## Evidence

- authority + dash_state_kotoba_parity (+ existing dash_state unit tests)
- cljs path uses same `oracle/ready?` gate as fleet.inventory / task.plan

## Related

- inventory Next: incremental cljs rewire of remaining catalog hosts
- murakumo#86–#122 product-shell + cljs load pattern
