# ADR-260728: Product Value ABI v1 — schedule pick-fold champ

Status: accepted after task/peer PVA (#118)

## Decision

Expand Product Value ABI v1 for schedule n>3 host-fold champ presence:

| core | export | before | after |
|---|---|---|---|
| `infer_schedule_core` | `pick-fold-step` | `has-champ` i64 | `champ [:option :i64]` + `if-some` |

Host bridges via `option-i64` (some(1) when a champion exists).

`overlay_peer_core` choose-via was PVA'd in murakumo#118 (optional path strings).

### Still host / unchanged

- schedule set projection + stable sort-by pick
- bit-packed `eligible?` flags (engine/checkpoint/fetch)
- rebalance classify-run-flags media has-*

## Evidence

- schedule parity + authority suite
- regenerated `infer_schedule_core.kir.edn`

## Related

- murakumo#112–#118 Product Value ABI trail
- murakumo#118 task failed? + peer choose-via
