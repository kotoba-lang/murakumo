# ADR-260728: Product Value ABI v1 — rebalance classify + task pick-fold

Status: accepted after task/peer PVA (#118); parallel to schedule pick-fold (#119)

## Decision

Expand Product Value ABI v1 for rebalance run classification and task n>3 fold:

| core | export | before | after |
|---|---|---|---|
| `infer_rebalance_core` | `classify-run-flags` | has-images/video/audio/tokens + is-swarm | `[:option :string]` ×5 (images/video/audio/swarm/tokens) |
| `task_plan_core` | `pick-task-fold-step` | `has-champ` i64 | `champ [:option :i64]` + `if-some` |

Host bridges via `option-string` / `option-i64`. Priority order for classify
unchanged: images > video > audio > swarm > tokens > none.

### Still host / unchanged

- rebalance demand-from-runs reduce + placement moves (still cljc)
- schedule `eligible?` bit-pack (engine/checkpoint/fetch)
- schedule `pick-fold-step` PVA in #119

## Evidence

- rebalance + task parity + authority suite
- regenerated `infer_rebalance_core.kir.edn` + `task_plan_core.kir.edn`

## Related

- murakumo#112–#118 Product Value ABI trail
- murakumo#119 schedule pick-fold champ
