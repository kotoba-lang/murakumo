# ADR-260731: token/secret/starts-with string gates return profile-5 :bool

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#212 (blank?/ws? bool), compiler#451

## Decision

Continue the product-facing predicate cutover for pure string/time gates:

1. **token_core**: `expired?`, `scope-allows?`, `version-ok?`, `parts-present?` → `:bool`.
2. **secret_core**: `valid-env-var-name?`, `valid-path-ref-unix?` → `:bool`.
3. **starts-with?** → `:bool` in overlay_driver / overlay_runtime / cloud_plan /
   reconcile_plan.
4. Wrappers that re-export starts-with or string equality also become `:bool`:
   - cloud: `is-cmd-*?`, `is-flag-*?`, `is-positional-target?`
   - reconcile: `flag-is-dry-run?` / `flag-is-apply?` / `flag-is-watch?` /
     `flag-is-snapshot?` / `flag-is-dash?`
   - overlay_driver: `command-is-dial?`; `dial-ok-reason` takes `is-dial :bool`.
5. Hosts use `oracle/bool->host` (not `(= 1 (i64->host …))`).
6. Parity tests wrap guest predicates as `(if p 1 0)` for `compile-i64-cases`.

## Evidence

- KIR regenerated for token / secret / overlay_driver / overlay_runtime /
  cloud_plan / reconcile_plan cores
- Focused parity + authority green (see commit message)

## Follow-up

- `can?` / `needs-relay?` / `eligible-for-work?` / `lease-expired?` still `:i64`
- Ranking / pick-code predicates (`better-*`, `rank-better?`) stay `:i64` by design
- Other equality gates (`action-is-*`, `known-secret-name?`, `version-ok` siblings
  already done, `known-adapter?`, `reply-is-value?`, digit-val?, …) next batches
