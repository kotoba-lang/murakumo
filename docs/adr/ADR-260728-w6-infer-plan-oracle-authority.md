# ADR-260728: W6 product-shell oracle authority — infer.plan pure path

Status: accepted after report (#89) product-shell cutover

## Decision

Fourth dual-source cutover for distributed inference planning:

| layer | role |
|---|---|
| `kotoba/infer_plan_core.kotoba` | SSoT pure integer/string helpers |
| `resources/murakumo/oracle/infer_plan_core.kir.edn` | precompiled KIR artifact |
| `murakumo.kotoba.oracle` catalog `:infer-plan` | load + execute |
| `murakumo.infer.plan` (JVM) | `usable-bytes`, `GiB`/reserves, `choose-strategy-name`, `ok-mark` |

### Host remains

- `partition-layers` / `layer-weights` double walk — guest `partition-target`
  multiplies `wsum * cum-usable` and overflows i64 for multi-hundred-GB models
- cljs host-mirror for all pure helpers
- `largest-remainder` vector algebra (rebalance path separate)
- `report` double GiB display fields
- `:why` strings for choose-strategy (map from oracle name)

### Regeneration

Same `murakumo.kotoba-oracle-gen` catalog entry as other product-shell cores.

## Evidence

- `kotoba_oracle_authority_test` infer-plan suite
- existing `infer_plan_kotoba_parity_test` + `infer_test`

## Related

- inventory Next: expand catalog (infer/plan)
- murakumo#86–#89 product-shell authority
