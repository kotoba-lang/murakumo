# ADR-260731: join/gc/relay/action pure gates return profile-5 :bool

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#215/#216 (string gates + product gates), compiler#451

## Decision

Continue pure predicate cutover for remaining non-ranking gates:

1. **infer_join_core**: `needs-relay?`, `swarm-can?`, `native-can?`, `can?`,
   `eligible-for-work?` → `:bool`. `eligible-for-work?` takes `can-kind :bool`
   (was host-projected 0/1 `:i64`).
2. **infer_relay_core**: `lease-expired?` → `:bool`.
3. **infer_gc_core**: `target-met?`, `comfy-evictable?` → `:bool`
   (`rank-better?` stays `:i64` ranking code).
4. **infer_rebalance_core**: `node-online?` → `:bool`.
5. **reconcile_plan_core**: `action-is-satisfied?`, `action-is-place?` → `:bool`.
6. **secret_core**: `known-secret-name?` → `:bool`.
7. **deploy_plan_core**: `execution-observed?` → `:bool`.
8. Hosts use `oracle/bool->host`; parity wraps `(if p 1 0)`.

## Evidence

- KIR regenerated for the 7 cores above
- Focused parity + unit + authority green (see commit message)

## Follow-up

- Ranking / pick-code predicates stay `:i64` by design:
  `better-*`, `rank-better?`, `challenger-wins?`, `before-pool?`, `weight-pos?`
- Character classifiers stay `:i64` codes: `digit-val?`, `alnum-char?`
- `move-needed` (0/1 pool-diff code) may convert later if treated as pure bool
