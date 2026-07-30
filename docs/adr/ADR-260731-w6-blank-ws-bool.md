# ADR-260731: blank?/ws? and derived string predicates are profile-5 :bool

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#211, compiler#451

## Decision

Convert whitespace/blank helpers and their product-facing derivative
predicates from `:i64` 0/1 to `:bool` across pure-planner cores:

1. `ws?` / `blank?` (report, secret, deploy, provision, reconcile, identity,
   component-authority, overlay-driver)
2. Derivatives: `missing-manifest?`, `missing-operator-seed?`,
   `operator-seed-missing?`, `valid-env-var-name?`, `valid-path-ref-unix?`,
   `identifier?` / `identifier-len-ok?` (is-blank arg `:bool`),
   `absolute-unix-git-bin?`

Hosts use `oracle/bool->host`. Internal `(= (ws? …) 1)` becomes `(ws? …)`.

## Evidence

- Live KIR regenerated for touched modules
- Focused parity + authority suites green

## Follow-up

- Other `*?` ranking/compare helpers (`starts-with?`, `command-is-dial?`,
  `known-secret-name?`, `better-*`) remain `:i64` where used as codes
