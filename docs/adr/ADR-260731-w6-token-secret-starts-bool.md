# ADR-260731: token/starts-with string gates return profile-5 :bool

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#212 (blank?/ws?), #214 (valid-env/path), compiler#451

## Decision

Continue the product-facing predicate cutover for pure string/time gates:

1. **token_core**: `expired?`, `scope-allows?`, `version-ok?`, `parts-present?` → `:bool`.
2. **starts-with?** → `:bool` in overlay_driver / overlay_runtime / cloud_plan /
   reconcile_plan.
3. Wrappers that re-export starts-with or string equality also become `:bool`:
   - cloud: `is-cmd-*?`, `is-flag-*?`, `is-positional-target?`
   - reconcile: `flag-is-dry-run?` / `flag-is-apply?` / `flag-is-watch?` /
     `flag-is-snapshot?` / `flag-is-dash?`
   - overlay_driver: `command-is-dial?`; `dial-ok-reason` takes `is-dial :bool`.
4. Hosts use `oracle/bool->host` (not `(= 1 (i64->host …))`).
5. Parity tests wrap guest predicates as `(if p 1 0)` for `compile-i64-cases`.

Note: `valid-env-var-name?` / `valid-path-ref-unix?` landed separately in #214.

## Evidence

- KIR regenerated for token / overlay_driver / overlay_runtime /
  cloud_plan / reconcile_plan cores
- Focused parity + authority green (see commit message)

## Follow-up

- `can?` / `needs-relay?` / `eligible-for-work?` / `lease-expired?` still `:i64`
- Ranking / pick-code predicates (`better-*`, `rank-better?`) stay `:i64` by design
- Other equality gates (`action-is-*`, `known-secret-name?`, `known-adapter?`,
  `reply-is-value?`, digit-val?, …) next batches
