# ADR-260728: W6 product-shell oracle authority — infer.engine pure path

Status: accepted after task.plan (#95) product-shell cutover

## Decision

Wire high-traffic pure cmd/string helpers of `murakumo.infer.engine` to kotoba SSoT:

| layer | role |
|---|---|
| `kotoba/infer_engine_core.kotoba` | SSoT |
| `resources/murakumo/oracle/infer_engine_core.kir.edn` | precompiled KIR |
| `murakumo.kotoba.oracle` catalog `:infer-engine` | load + execute |
| JVM `default-rpc-port` / `rpc-server-cmd` / `endpoint` / `i64-str` / head-cmd fragments / embed front+back / mlx-moe front+opt flags / mlx-launch front | delegate to oracle |

### Still cljc host

- `workers` / `serving` / `head-span` plan walks
- CSV join of variable-length worker lists
- `pr-str` prompt quoting + `extra-args` / `moe-override` host joins
- `mlx-hosts` map assembly
- `commands` engine dispatch
- cljs host-mirror for pure helpers

## Evidence

- authority + infer_engine_kotoba_parity

## Related

- inventory Next: bulk catalog gen / remaining low-priority verticals / Delivery shells
- murakumo#86–#95 product-shell pattern
