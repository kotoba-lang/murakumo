# ADR-260728: W6 product-shell oracle authority — infer.engine pure path

Status: accepted after task.plan (#95) product-shell cutover

## Decision

Wire engine cmd-string pure helpers to kotoba SSoT:

| layer | role |
|---|---|
| `kotoba/infer_engine_core.kotoba` | SSoT string/cmd fragments |
| `resources/murakumo/oracle/infer_engine_core.kir.edn` | precompiled KIR |
| catalog `:infer-engine` | load + execute |
| JVM `rpc-server-cmd` / `endpoint` / `head-cmd-*` / mlx/embed fragments | oracle |

### Host remains

- plan vector walks (`workers` / `serving` / variable-arity CSV join)
- `pr-str` prompt quoting, optional `extra-args` / `-ot` join
- cljs host-mirror string assembly

## Evidence

- authority suite + `infer_engine_kotoba_parity_test` / `infer_test`

## Related

- inventory Next: expand catalog (engine)
- murakumo#86–#95 product-shell pattern
