# ADR-260731: T6.4 remainder — task.plan + reconcile.plan delete cljs mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after plan+credits #249)
- Depends: #249 infer plan+credits, preload contract (#233)

## Decision

1. **`murakumo.task.plan`** drops all dual-source cljs pure mirrors and
   `#?(:clj … :cljs mirror-…)` branches. Pure helpers require shipped
   `:task-plan` KIR via `oracle/require-ready!`. Host remains admit/prepare
   folds, set membership projection, sort-by node-score, map assembly.
2. **`murakumo.reconcile.plan`** drops all `mirror-*` / `try-oracle` /
   `oracle-str-const` dual-source pure reimplementations. Pure tokens +
   desired/deficit/action/flag classifiers require shipped `:reconcile-plan`
   KIR. Host remains set algebra, pick-targets sort, reason strings, parse-flags fold.
3. **Preload guarantee** same as prior T6.4 mirror-delete hosts
   (`ops.cljs` / `task.cljs` already preload `:task-plan`).

## Non-claims

- report/config/deploy/provision/cloud/dash still keep cljs mirrors
- T8.3 production AOT; W4 recursive values

## Evidence

- task-plan + reconcile host/parity suites green
- No dual-source mirror bodies remain in either ns
