# ADR-260731: blank?/ws? and string-gate helpers return profile-5 :bool

- Status: accepted
- Date: 2026-07-31
- Depends: compiler#451, murakumo#207–#210

## Decision

1. `blank?` / `ws?` / `err-ws?` return `:bool` across product-shell cores.
2. Call sites use `(if (ws? …) …)` / `(if (blank? …) …)` instead of
   `(= (ws? …) 1)`.
3. Wrappers that re-export blankness are also `:bool`:
   `identifier?`, `identifier-len-ok?` (is-blank :bool), `missing-manifest?`,
   `missing-operator-seed?`, `operator-seed-missing?`, `absolute-unix-git-bin?`.
4. Hosts use `oracle/bool->host` for these predicates.

## Evidence

- KIR regenerated for 9 cores
- 130 tests / 2017 assertions across affected parity + authority, 0 failures

## Follow-up

- `valid-env-var-name?` / `valid-path-ref-unix?` / `starts-with?` / `expired?` /
  `scope-allows?` / `authorized?` / `can?` still return `:i64` 0/1
