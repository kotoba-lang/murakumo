# ADR-260728: Product Value ABI v1 — schedule pick-fold + overlay-peer choose-via

Status: accepted after connect/report PVA (#116)

## Decision

Expand Product Value ABI v1 for remaining presence-gate `has-*` sentinels:

| core | export | before | after |
|---|---|---|---|
| `infer_schedule_core` | `pick-fold-step` | `has-champ` i64 | `champ [:option :i64]` + `if-some` |
| `overlay_peer_core` | `choose-via` | `has-direct` / `health-down` / `has-relay` i64 | `direct?` / `down?` / `relay?` `[:option :i64]` |

Host bridges via `option-i64` (some(1) when true). Schedule eligibility
bit-pack (`eligible?` flags 1/2/4/8) stays packed i64 — not individual has-*.

### Still host / unchanged

- schedule set projection + stable sort-by pick
- peer catalog/remember map folds
- bit-packed `eligible?` flags (engine/checkpoint/fetch)

## Evidence

- schedule + peer parity + authority suite
- regenerated `infer_schedule_core.kir.edn` + `overlay_peer_core.kir.edn`

## Related

- murakumo#112–#116 Product Value ABI trail
