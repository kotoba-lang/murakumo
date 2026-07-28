# ADR-260728: Product Value ABI v1 — task failed? + overlay peer choose-via

Status: accepted after connect/report PVA (#116)

## Decision

Expand Product Value ABI v1 to task failure gates and overlay path selection:

| core | export | before | after |
|---|---|---|---|
| `task_plan_core` | `failed?` | exit-present + exit-code + has-error | `[:option :i64]` exit + `[:option :string]` error |
| `overlay_peer_core` | `choose-via` | has-direct / health-down / has-relay | optional direct/relay strings + health string |

Host bridges via `option-i64` / `option-string`.

### Still host / unchanged

- task admit/prepare folds, label/role set membership
- peer catalog/remember map folds, path candidate projection
- schedule bit-packed eligibility flags
- rebalance classify-run-flags media has-* (later PVA)

## Evidence

- task_plan + overlay_peer parity + authority suite
- regenerated `task_plan_core.kir.edn` + `overlay_peer_core.kir.edn`

## Related

- murakumo#112–#116 Product Value ABI v1 slices
- inventory Next: PVA expand (schedule bit-flags, overlay-peer choose-via, …)
